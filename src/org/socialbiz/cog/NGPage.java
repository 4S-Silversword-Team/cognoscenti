/*
 * Copyright 2013 Keith D Swenson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors Include: Shamim Quader, Sameer Pradhan, Kumar Raja, Jim Farris,
 * Sandia Yang, CY Chen, Rajiv Onat, Neal Wang, Dennis Tam, Shikha Srivastava,
 * Anamika Chaudhari, Ajay Kakkar, Rajeev Rastogi
 */

package org.socialbiz.cog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import org.socialbiz.cog.exception.NGException;
import org.socialbiz.cog.exception.ProgramLogicError;
import org.socialbiz.cog.mail.MailFile;
import org.socialbiz.cog.mail.ScheduledNotification;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.workcast.json.JSONArray;
import org.workcast.json.JSONObject;

/**
* NGPage is a Container that represents a Workspace.
* "Page" is the old term, "Leaf" is also an old term, avoid these.
* The current term for the user interaction is "Workspace"
*/
public class NGPage extends ContainerCommon implements NGContainer
{

    public PageInfoRecord pageInfo;
    public ReminderMgr reminderMgr;

    protected String[] displayNames;
    protected Vector<NGSection> sectionElements = null;
    protected NGBook prjSite;
    protected Vector<String> existingIds = null;


    public static final int CAT_ANONYMOUS = -1;
    public static final int CAT_PUBLIC    = 0;



    //Least Recently Used Cache .... keep copies of the last ten
    //page objects in memory for reuse
    protected static LRUCache pageCache = new LRUCache(10);

    // Data path must be known, and this is gotten from the session
    // It can not change over the course of a server instance, so
    // we can cache it here for use to find other data files.
    protected static String    dataPath;


    public NGPage(File theFile, Document newDoc, NGBook site) throws Exception
    {
        super(theFile, newDoc);

        //migration code from a time when the key was ONLY the file name.
        //need to store the key in the file itself, and get that from the
        //filename properly
        String key = getKey();
        if (key==null || key.length()==0) {
            migrateKeyValue(theFile);
        }

        //initially page names consist entirely of address
        String smallName = theFile.getName();
        if (smallName.endsWith(".sp")) {
            smallName = smallName.substring(0, smallName.length()-3);
        }
        displayNames = new String[] {smallName};

        pageInfo = requireChild("pageInfo", PageInfoRecord.class);

        displayNames = pageInfo.getPageNames();

        if (site==null) {
            //site==null only when read an existing workspace, not creating
            //in this case, the project should have a site setting.
            String siteKey = pageInfo.getSiteKey();

            if (siteKey==null || siteKey.length()==0 || "main".equals(siteKey)) {
                //silly archaic default, originally pages 'assumed' a site by default
                //so if the value was missing, we assumed a particular value.
                //At one time the default site was "main"
                //but later changed to "mainbook".  Only one server still has this
                //problem.  Time to fix that server and remove this code.
                siteKey="mainbook";
                pageInfo.setSiteKey("mainbook");
                System.out.println("This server still has a workspace without any site key setting: "+key);
            }

            //this throws an exception if book not found
            prjSite = NGBook.readSiteByKey(siteKey);
        }
        else {
            prjSite = site;
            pageInfo.setSiteKey(site.getKey());
        }

        NGSection mAtt = getRequiredSection("Attachments");
        SectionAttachments.assureSchemaMigration(mAtt, this);

        getRequiredSection("Comments");
        getRequiredSection("Folders");

        //forces the Tasks section, and also the process initialization
        getProcess();

        //SCHEMA MIGRATION to remove Public Attachments Section
        //'Attachments' is the ONLY place documents should be stored.
        //This code is the only place that manipulates the deprecated "Public Attachments" section.
        //in order to deprecate Public Attachments, check to see if
        //there are any attached files init, and move them to
        //the regular attachments, as public visible regular attachments
        NGSection pAtt = getSection("Public Attachments");
        if (pAtt!=null)
        {
            SectionAttachments.moveAttachmentsFromDeprecatedSection(pAtt);
            removeSection("Public Attachments");
        }

        //This is the ONLY place you should see these
        //deprecated sections, check to see if
        //there are any leaflets in there, and move them to the
        //main comments section.
        assertNoSectionWithThisName("Public Comments");
        assertNoSectionWithThisName("See Also");
        assertNoSectionWithThisName("Links");
        assertNoSectionWithThisName("Description");
        assertNoSectionWithThisName("Public Content");
        assertNoSectionWithThisName("Notes");
        assertNoSectionWithThisName("Author Notes");
        assertNoSectionWithThisName("Private");
        assertNoSectionWithThisName("Member Content");
        assertNoSectionWithThisName("Poll");
        assertNoSectionWithThisName("Geospatial");

        //migrate the old forms of members and admins roles to the new form
        //the old form was a "userlist" element, with "user" elements below that
        //with permissions PM, M, PA, and A.  Move those to the real "roles"
        //and eliminate the old userlist element.
        NGRole newMemberRole = getRequiredRole("Members");
        NGRole newAdminRole = getRequiredRole("Administrators");

        DOMFace userList = pageInfo.getChild("userlist", DOMFace.class);
        if (userList!=null)
        {
            Vector<DOMFace> users = userList.getChildren("user", DOMFace.class);
            for (DOMFace ele : users) {
                String id = ele.getAttribute("id");
                AddressListEntry user = AddressListEntry.newEntryFromStorage(id);
                String permis = ele.getAttribute("permission");
                if (permis.equals("M") || permis.equals("PA"))
                {
                    newMemberRole.addPlayer(user);
                }
                if (permis.equals("A"))
                {
                    newAdminRole.addPlayer(user);
                }
            }
            //now get rid of all former evidence.
            pageInfo.removeChildElement(userList.getElement());
        }

        //assure that the notify role exists
        getRequiredRole("Notify");

        //upgrade all the note, document, and task records
        cleanUpTaskUniversalId();
    }

    //this is the NGPage version, and a different approach is used for NGProj
    protected void migrateKeyValue(File theFile) throws Exception {
        String fileName = theFile.getName();
        String fileKey = SectionUtil.sanitize(fileName.substring(0,fileName.length()-3));
        setKey(fileKey);
    }

    private void assertNoSectionWithThisName(String name) throws Exception {
        if (getSection(name)!=null) {
            //this will automatically convert it to leaflet format
            removeSection(name);
        }
    }


    /**
    * schema migration ...
    * make sure that all tasks have universal ids.
    * do this here because the GoalRecord constructor
    * does not easily know what the container is.
    */
    protected void cleanUpTaskUniversalId() throws Exception {

        super.cleanUpNoteAndDocUniversalId();

        for (GoalRecord goal : getAllGoals()) {
            String uid = goal.getUniversalId();
            if (uid==null || uid.length()==0) {
                uid = getContainerUniversalId() + "@" + goal.getId();
                goal.setUniversalId(uid);
            }
            long lastModTime = goal.getModifiedDate();
            if (lastModTime<=0) {
                String lastModUser = "";
                for (HistoryRecord hist : goal.getTaskHistory(this)) {
                    if (hist.getTimeStamp()>lastModTime) {
                        lastModTime = hist.getTimeStamp();
                        lastModUser = hist.getResponsible();
                    }
                }
                goal.setModifiedDate(lastModTime);
                goal.setModifiedBy(lastModUser);
            }
        }
    }


    /**
    * Set all static values back to their initial states, so that
    * garbage collection can be done, and subsequently, the
    * class will be reinitialized.
    */
    public synchronized static void clearAllStaticVars()
    {
        pageCache.emptyCache();
        dataPath = null;
    }

    /**
    * This must be called and initialized BEFORE attempting to access
    * any page.
    */
    public static void initDataPath(String path)
    {
        pageCache.emptyCache();
        dataPath = path;
    }


    /*
    public static File getPathInDataFolder(String p)
        throws Exception
    {
        if (dataPath==null)
        {
            throw new NGException("nugen.exception.datapath.not.initialized",null);
        }
        if (p.indexOf('/')>=0)
        {
            throw new NGException("nugen.exception.path.have.slash", new Object[]{p});
        }
        File theFile = new File(dataPath, p);
        String fullPath = theFile.getPath();

        String cleanUp1 = fullPath.substring(0,dataPath.length()).toLowerCase().replace('\\','/');
        String cleanUp2 = dataPath.toLowerCase().replace('\\','/');

        //this is a security check:
        //The result of combining the path in this way, must result in a path
        //that is still within the data folder, so check that the cannonical
        //path starts with the data folder path.
        if (!cleanUp1.equals(cleanUp2))
        {
            throw new NGException("nugen.exception.wrong.path", new Object[]{dataPath,fullPath,cleanUp2,cleanUp1});
        }

        return new File(fullPath);
    }
*/

    /**
     * To an existing project, add all the (1) Action Items (2) Roles of an
     * existing project.
     * @param ar is needed to get the current logged in user and the current time
     * @param template is the project to get the ActionItems/Roles from
     */
    public void injectTemplate(AuthRequest ar, NGPage template)
        throws Exception
    {
        String bestId = ar.getBestUserId();

        //copy all of the tasks, but no status.
        for (GoalRecord templateGoal : template.getAllGoals()) {
            GoalRecord newGoal = createGoal(ar.getBestUserId());
            newGoal.setSynopsis(templateGoal.getSynopsis());
            newGoal.setDescription(templateGoal.getDescription());
            newGoal.setPriority(templateGoal.getPriority());
            newGoal.setDuration(templateGoal.getDuration());
            newGoal.setCreator(bestId);
            newGoal.setState(BaseRecord.STATE_UNSTARTED);
            newGoal.setRank(templateGoal.getRank());
            newGoal.setModifiedBy(bestId);
            newGoal.setModifiedDate(ar.nowTime);
        }

        //copy all of the roles - without the players
        for (NGRole role : template.getAllRoles()) {
            String roleName = role.getName();
            NGRole alreadyExisting = getRole(roleName);
            if (alreadyExisting==null) {
                createRole(roleName,role.getDescription());
            }
        }

    }


    public static NGPage readPageAbsolutePath(File theFile) throws Exception {
        if (!theFile.exists()) {
            throw new NGException("nugen.exception.file.not.exist", new Object[]{theFile});
        }
        try {
            String fullFilePath = theFile.toString();

            //look in the cache
            NGPage newPage = pageCache.recall(fullFilePath);
            if (newPage==null) {
                Document newDoc;
                InputStream is = new FileInputStream(theFile);
                newDoc = DOMUtils.convertInputStreamToDocument(is, false, false);
                is.close();
                if (NGBook.fileIsInDataPath(theFile)) {
                    newPage = new NGPage(theFile, newDoc, null);
                }
                else {
                    newPage = new NGProj(theFile, newDoc, null);
                }
            }

            //store into the cache.  Note, there is possibility
            //that another thread picks this up before we are done with it...
            //need to implement page lock mechanism to prevent this, and that
            //means having reliable clean-up code to store at the end of use.
            //Probably should lock the file reliably....
            pageCache.store(fullFilePath, newPage);
            return newPage;
        }
        catch (Exception e) {
            throw new NGException("nugen.exception.unable.to.read.file",new Object[]{theFile}, e);
        }
    }

    /**
     * Clears the current page from the cache.
     * This is the global "rollback" function.  It does not undo any changes
     * but if this is called before discarding the reference to a page object,
     * and without saving that page, then
     * the next access to the page will be from the prior saved version of the page.
     * This method should be called whenever an exception is caught (at the root level)
     * so that any possible changes during processing before the exception is thrown away.
     */
    public static void removeCachedPage(String fullFilePath) {
        pageCache.unstore(fullFilePath);
    }


    @Override
    public void saveFile(AuthRequest ar, String comment) throws Exception {
        try {
            setLastModify(ar);
            save(ar.getBestUserId(), ar.nowTime, comment, ar.getCogInstance());

            if (prjSite!=null) {
                prjSite.saveFile(ar, comment);
            }
        }
        catch (Exception e) {
            throw new NGException("nugen.exception.unable.to.write.file",
                    new Object[] { getFilePath().toString() }, e);
        }
    }

    //Added new method without using ar parameter to save contents (Need to discuss)
    @Override
    public void save(String modUser, long modTime, String comment, Cognoscenti cog) throws Exception
    {
        pageInfo.setModTime(modTime);
        pageInfo.setModUser(modUser);

        saveWithoutMarkingModified(modUser, comment, cog);
    }


    //This is for config changes, NOT content changes
    public void saveWithoutMarkingModified(String modUser, String comment, Cognoscenti cog) throws Exception
    {
        try {
            save();

            //update the in memory index because the file has changed
            refreshOutboundLinks(cog);

            //Update blocking Queue
            NGPageIndex.postEventMsg(this.getKey());
        }
        catch (Exception e) {
            throw new NGException("nugen.exception.unable.to.write.file",
                    new Object[]{getFilePath().toString()}, e);
        }
    }

    /**
     * This should be called everytime the page contents are changed in a way
     * that might effect the links on the page.
     */
    private void refreshOutboundLinks(Cognoscenti cog) throws Exception {
        String key = getKey();
        NGPageIndex ngpi = cog.getContainerIndexByKey(key);
        if (ngpi == null) {
            throw new NGException("nugen.exception.refresh.links", new Object[] { key });
        }
        ngpi.unlinkAll();
        ngpi.buildLinks(this);

        // check if there is new email, and put this in the index as well
        if (countEmailToSend() > 0) {
            cog.projectsWithEmailToSend.add(key);
        }
    }



    /**
    * This will mark the page as deleted at the time of the request
    * and by the person in the request.
    */
    public void markDeleted(AuthRequest ar)
    {
        pageInfo.setDeleted(ar);
    }
    /**
    * This will unmark the page from being deleted, clearing any earlier
    * setting that the page was deleted
    */
    public void markUnDeleted(AuthRequest ar)
    {
        pageInfo.clearDeleted();
    }

    @Override
    public boolean isDeleted()
    {
        return pageInfo.isDeleted();
    }
    public long getDeleteDate()
    {
        return pageInfo.getDeleteDate();
    }
    public String getDeleteUser()
    {
        return pageInfo.getDeleteUser();
    }


    public NGSection getSectionOrFail(String sectionNameToLookFor)
        throws Exception
    {
        NGSection ngs = getSection(sectionNameToLookFor);
        if (ngs==null)
        {
            throw new NGException("nugen.exception.unable.to.locate.section", new Object[]{sectionNameToLookFor,getKey()});
        }
        return ngs;
    }

    public NGSection getSection(String sectionNameToLookFor) throws Exception {
        for (NGSection sec : getAllSections()) {
            String thisName = sec.getName();
            if (thisName == null || thisName.length() == 0) {
                throw new NGException("nugen.exception.section.not.have.name", null);
            }
            if (sectionNameToLookFor.equals(thisName)) {
                return sec;
            }
        }
        return null;
    }

    /**
    * Get a section, creating it if it does not exist yet
    */
    private NGSection getRequiredSection(String secName)
        throws Exception
    {
        if (secName==null)
        {
            throw new RuntimeException("getRequiredSection was passed a null secName parameter.");
        }
        NGSection sec = getSection(secName);
        if (sec==null)
        {
            createSection(secName, null);
            sec = getSection(secName);
        }
        return sec;
    }



    /**
    * To create a new, empty, section, call this method.
    */
    public void createSection(String secName, AuthRequest ar)
        throws Exception
    {
        SectionDef sd = SectionDef.getDefByName(secName);
        if (sd==null)
        {
            throw new NGException("nugen.exception.no.section.with.given.name", new Object[]{secName});
        }
        createSection(sd, ar);
    }


    /**
    *  The AuthRequest object is needed to record the user and time
    * of modification.  In the situation that a required section is
    * missing (such as the Tasks section which is supposed to be created
    * at the moment the page is created, but there are some old pages
    * today without Tasks sections) then it does not really matter to
    * record who added this required section.  In that case, pass a null
    * in for the AuthRecord, and nothing will be recorded.
    */
    private void createSection(SectionDef sd, AuthRequest ar)
        throws Exception
    {
        if (sd==null)
        {
            throw new RuntimeException("createSection was passed a null sd parameter");
        }
        //clear the cached vector, force regeneration in any case
        sectionElements = null;
        String secName = sd.getTypeName();
        Vector<NGSection> allSections = getChildren("section", NGSection.class);
        for (NGSection sec : allSections)
        {
            if (secName.equals(sec.getAttribute("name")))
            {
                return;  //already created
            }
        }

        createChildWithID("section", NGSection.class, "name", secName);
    }


    public void removeSection(String nameToRemove)
        throws Exception
    {
        NGSection lameDuck = getSection(nameToRemove);
        if (lameDuck==null)
        {
            throw new NGException("nugen.exception.unable.to.remove.section", new Object[]{nameToRemove});
        }
        //testing
        if (!nameToRemove.equals(lameDuck.getName()))
        {
            throw new ProgramLogicError("Got wrong section ("
                   +nameToRemove+" != "+lameDuck.getName()+").");
        }
        SectionDef def = lameDuck.def;
        if (def.required)
        {
            throw new NGException("nugen.exception.section.required",
                    new Object[]{def.displayName,def.getTypeName(),nameToRemove,lameDuck.getName()});
        }

        //attempt to convert the contents, if any, to a Leaflet
        SectionFormat sf = def.format;
        NGSection notes = getRequiredSection("Comments");
        if (sf instanceof SectionPrivate)
        {
            sf.convertToLeaflet(notes, lameDuck);
        }
        else if (sf instanceof SectionWiki)
        {
            sf.convertToLeaflet(notes, lameDuck);
        }

        //clear the cached vector, force regeneration in any case
        sectionElements = null;
        removeChild(lameDuck);
    }


    public Vector<NGSection> getAllSections()
        throws Exception
    {
        if (sectionElements==null)
        {
            //fetch it and cache it here
            sectionElements = getChildren("section", NGSection.class);
        }
        return sectionElements;
    }

    public boolean hasSection(String secName)
        throws Exception
    {
        for (NGSection sec : getAllSections())
        {
            if (secName.equals(sec.getName()))
            {
                return true;
            }
        }
        return false;
    }

    /**
    * Move a section to a new position, either one position higher (up)
    * or one position lower (down).  First parameter is the section name,
    * second parameter is the direction  true=up, false=down
    */
    /*
    public void moveSection(String secName, boolean moveUp)
    {
        Vector<Element> sections = DOMUtils.getNamedChildrenVector(fEle, "section");

        for (int i=0; i<sections.size(); i++)
        {
            Element e = sections.elementAt(i);
            String secNameFound = e.getAttribute("name");
            if (secName.equals(secNameFound))
            {
                if (moveUp)
                {
                    if (i==0)
                    {
                        return;   //already first, can't move up
                    }
                    Element prevE = sections.elementAt(i-1);
                    fEle.insertBefore(e, prevE);
                }
                else
                {
                    if (i>=sections.size()-1)
                    {
                        return;   //already last, can't move down
                    }
                    Element nextE = sections.elementAt(i+1);
                    fEle.insertBefore(nextE, e);
                }
                //clear the cached vector so it can get the new order
                sectionElements = null;
                return;   //we found something, we are done
            }

            //for now we are silently ignoring requests to move sections
            //that we can not find.
        }
    }
    */

    /**
    * This is the unique ID of the entire project
    * across all sites.  It is up to the system to
    * make sure this is created and maintained unique
    * and it must never be changed (or links will be
    * broken).  Linking should be by name if possible.
    */
    @Override
    public String getKey() {
        return pageInfo.getKey();
    }
    public void setKey(String newKey) {
        pageInfo.setKey(newKey);
    }

    /**
     * Returns the current full name of this page
     */
    @Override
    public String getFullName() {
        if (displayNames == null) {
            return "Uninitialized (displayNames is null)";
        }
        if (displayNames.length == 0) {
            return "Uninitialized (displayNames contains zero items)";
        }
        return displayNames[0];
    }

    public String[] getPageNames()
    {
        return displayNames;
    }

    public void setPageNames(String[] newNames)
    {
        pageInfo.setPageNames(newNames);
        displayNames = pageInfo.getPageNames();
    }


    public String getUpstreamLink() {
        return pageInfo.getScalar("upstream");
    }

    public void setUpstreamLink(String uStrm) {
        pageInfo.setScalar("upstream", uStrm);
    }

    public void findLinks(Vector<String> v)
        throws Exception
    {
        for (NGSection sec : getAllSections())
        {
            sec.findLinks(v);
        }
    }

    public void findTags(Vector<String> v)
        throws Exception
    {
        for (NoteRecord note : getAllNotes())
        {
            note.findTags(v);
        }
    }


    @Override
    public long getLastModifyTime()
        throws Exception
    {
        long timeAttrib = pageInfo.getModTime();
        if (timeAttrib>0)
        {
            return timeAttrib;
        }

        //currently we have a lot of pages without last modified set, so when it
        //finds that the case, search through the sections, and find the latest
        //section modification.  This code can be removed once all the existing
        //pages get edited and upgraded.
        long latestSecTime = 0;
        for (NGSection sec : getAllSections())
        {
            long secTime = sec.getLastModifyTime();
            if (secTime>latestSecTime)
            {
                latestSecTime = secTime;
            }
        }
        return latestSecTime;
    }

    public String getLastModifyUser()
        throws Exception
    {
        String modUser = pageInfo.getModUser();
        if (modUser!=null)
        {
            return modUser;
        }

        //currently we have a lot of pages without last modified set, so when it
        //finds that the case, search through the sections, and find the latest
        //section modification.  This code can be removed once all the existing
        //pages get edited and upgraded.
        long latestSecTime = 0;
        for (NGSection sec : getAllSections())
        {
            long secTime = sec.getLastModifyTime();
            if (secTime>latestSecTime)
            {
                latestSecTime = secTime;
                modUser = sec.getLastModifyUser();
            }
        }
        return modUser;
     }

    public void setLastModify(AuthRequest ar)
        throws Exception
    {
        ar.assertLoggedIn("Must be logged in in order to modify page.");
        pageInfo.setModTime(ar.nowTime);
        pageInfo.setModUser(ar.getBestUserId());
    }


    public NGBook getSite() {
        if (prjSite==null) {
            //this will prove that this always returns a non-null value.
            throw new RuntimeException("Program Logic Error: something is wrong with NGPage object which has a null site ... this should never happen.");
        }
        return prjSite;
    }

    public String getSiteKey() {
        return pageInfo.getSiteKey();
    }


    public void setSite(NGBook ngb) {
        if (ngb==null) {
            throw new RuntimeException("setSite called with null parameter.   Should not be using the 'default site' concept any more, this exception is checking to see if it ever happens");
        }
        pageInfo.setSiteKey(ngb.getKey());
        prjSite = ngb;
    }


    /**
    * Override the superclass implementation to add a license for the process
    */
    @Override
    public Vector<License> getLicenses() throws Exception {
        Vector<License> vc = super.getLicenses();
        vc.add(new LicenseForProcess(getProcess()));
        return vc;
    }

    public License createLicense(String userId, String role, long endDate,
            boolean readOnly) throws Exception{
        String id = IdGenerator.generateKey();
        License lr = addLicense(id);
        lr.setTimeout(endDate);   //one year later
        lr.setCreator(userId);
        lr.setRole(role);
        lr.setReadOnly(readOnly);
        return lr;
    }

    @Override
    public boolean isValidLicense(License lr, long time) throws Exception {
        if (super.isValidLicense(lr, time)) {
            return true;
        }
        if (lr instanceof LicenseForUser) {
            //check all the action items, the license will be valid if the person
            //is still assigned to a action items, and the license is a LicenseForUser
            AddressListEntry ale = new AddressListEntry(lr.getCreator());
            for (GoalRecord goal : this.getAllGoals()) {
                if (!goal.isPassive() && goal.isAssignee(ale)) {
                    //passive action items from other project should only effect those other projects,
                    //and should not allow anyone into a linked sub project.
                    //Active action items should allow anyone assigned to be treated as a member.
                    return true;
                }
            }
        }
        return false;
    }


    ////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////

    /**
    * Build in ability to test whether a page is rendered to
    * correct XHTML.  If all is correct there is no response,
    * bu if there is an error, an exception is thrown.
    * This test primarily is to render the page, and then
    * to parse the result as XML.  If the result is valid XML
    * then we have some assurance that nothing accidental was
    * included in the page.  Perhaps we can test the DOM and see
    * if things are nested correctly ... in the future.
    */
    public void testRender(AuthRequest ar)
        throws Exception
    {
        for (int limitLevel=0; limitLevel<=4; limitLevel++)
        {
            for (NGSection sec : getAllSections())
            {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                Writer testOut = new OutputStreamWriter(buf, "UTF-8");

                AuthRequest ar4test = new AuthDummy(ar.getUserProfile(), testOut, ar.getCogInstance());
                //ar4test.maxLevel = limitLevel;   <--no longer implemented, need to do something else
                ar4test.setPageAccessLevels(this);

                //write a dummy containing tag -- everything else will be within this
                testOut.write("<editpage>");
                //conclude the containing tag
                testOut.write("</editpage>");
                testOut.flush();
                byte[] b = buf.toByteArray();
                ByteArrayInputStream is = new ByteArrayInputStream(b);

                //parse again
                try {
                    DOMUtils.convertInputStreamToDocument(is, false, false);
                }
                catch (Exception e) {
                    throw new NGException("nugen.exception.error.in.section", new Object[] {
                            sec.getName(), new String(b, "UTF-8") }, e);
                }
            }
        }
    }


    //a page always has a process, so if asked for, and we can't find
    //it, then we create it.
    public ProcessRecord getProcess()
        throws Exception
    {
        NGSection sec = getRequiredSection("Tasks");
        ProcessRecord pr = sec.requireChild("process", ProcessRecord.class);
        if (pr.getId() == null || pr.getId().length() == 0)  {
            // default values
            pr.setId(getUniqueOnPage());
            pr.setState(BaseRecord.STATE_UNSTARTED);
        }
        return pr;
    }


    /**
    * Returns all the action items for a project.
    */
    public List<GoalRecord> getAllGoals() throws Exception {
        NGSection sec = getRequiredSection("Tasks");
        return SectionTask.getAllTasks(sec);
    }

    public JSONArray getJSONGoals() throws Exception {
        JSONArray val = new JSONArray();
        for (GoalRecord goal : getAllGoals()) {
            val.put(goal.getJSON4Goal(this));
        }
        return val;
    }


    /**
    * Find the requested action item, or throw an exception
    */
    public GoalRecord getGoalOrFail(String id) throws Exception {
        NGSection sec = getRequiredSection("Tasks");
        return SectionTask.getTaskOrFail(sec, id);
    }

    public GoalRecord getGoalOrNull(String id) throws Exception {
        NGSection sec = getRequiredSection("Tasks");
        return SectionTask.getTaskOrNull(sec, id);
    }

    /**
    * Creates an action item in a project without any history about creating it
    */
    public GoalRecord createGoal(String requesterId)
        throws Exception
    {
        String id = getUniqueOnPage();
        NGSection ngs = getSectionOrFail("Tasks");
        GoalRecord goal = ngs.createChildWithID("task", GoalRecord.class, "id", id);
        goal.setCreator(requesterId);
        String uid = getContainerUniversalId() + "@" + id;
        goal.setUniversalId(uid);
        goal.setRank(32000000);
        goal.setPassive(false);
        renumberGoalRanks();
        return goal;
    }

    /**
    * Create a new goal that is subordinant to another
    */
    public GoalRecord createSubGoal(GoalRecord parent, String requesterId) throws Exception {
        GoalRecord goal = createGoal(requesterId);
        goal.setParentGoal(parent.getId());
        goal.setDueDate(parent.getDueDate());
        parent.setState(BaseRecord.STATE_WAITING);
        return goal;
    }


    public JSONArray getJSONAttachments(AuthRequest ar) throws Exception {
        JSONArray val = new JSONArray();
        for (AttachmentRecord doc : getAccessibleAttachments(ar.getUserProfile())) {
            if (doc.isDeleted()) {
                continue;
            }
            val.put(doc.getJSON4Doc(ar, this));
        }
        return val;
    }


    @Override
    public List<HistoryRecord> getAllHistory()
        throws Exception
    {
        return getProcess().getAllHistory();
    }

    @Override
    public HistoryRecord createNewHistory()
        throws Exception
    {
        HistoryRecord newHist = getProcess().createPartialHistoryRecord();
        newHist.setId(getUniqueOnPage());
        return newHist;
    }




    public void genProcessData(AuthRequest ar)
        throws Exception
    {
        ProcessRecord process = getProcess();

        ar.resp.setContentType("text/xml;charset=UTF-8");
        Document doc = DOMUtils.createDocument("process");
        String schema =  ar.baseURL + "rest/xsd/Page.xsd";
        DOMUtils.setSchemAttribute(doc.getDocumentElement(), schema);
        Element processEle = doc.getDocumentElement();
        String processurl = ar.baseURL + "p/" + getKey() + "/process.wfxml";
        process.fillInWfxmlProcess(doc, processEle, this, processurl);
        DOMUtils.writeDom(doc, ar.w);
    }

    public void genActivityData(AuthRequest ar, String id)
        throws Exception
    {
        //todo: not sure these two lines are required
        getProcess();
        getRequiredSection("Tasks");

        ar.resp.setContentType("text/xml;charset=UTF-8");
        Document doc = DOMUtils.createDocument("activity");
        Element actEle = doc.getDocumentElement();
        GoalRecord task = getGoalOrFail(id);
        String processurl = ar.baseURL + "p/" + getKey() + "/process.wfxml";
        task.fillInWfxmlActivity(doc, actEle,processurl);
        DOMUtils.writeDom(doc, ar.w);
    }

    public String getPlainText(AuthRequest ar) throws Exception
    {
        StringWriter out = new StringWriter();

        for (int i=0; i<displayNames.length; i++)
        {
            out.write(displayNames[i]);
            out.write("\n");
        }

        for (NGSection sec : getAllSections())
        {
            SectionFormat formatter = sec.getFormat();
            formatter.writePlainText(sec, out);
        }

        return out.toString();
    }

    /**
    * Get a four digit numeric id which is unique on the page.
    */
    @Override
    public String getUniqueOnPage()
        throws Exception
    {
        existingIds = new Vector<String>();

        //this is not to be trusted any more
        for (NGSection sec : getAllSections())
        {
            sec.findIDs(existingIds);
        }

        //these added to be sure.  There is no harm in
        //being redundant.
        for (NoteRecord note : getAllNotes()) {
            existingIds.add(note.getId());
        }
        for (AttachmentRecord att : getAllAttachments()) {
            existingIds.add(att.getId());
        }
        for (GoalRecord task : getAllGoals()) {
            existingIds.add(task.getId());
        }
        for (MeetingRecord meeting : this.getMeetings()) {
            existingIds.add(meeting.getId());
            for (AgendaItem ai : meeting.getAgendaItems()) {
                existingIds.add(ai.getId());
            }
        }
        return IdGenerator.generateFourDigit(existingIds);
    }


    @Override
    public ReminderMgr getReminderMgr()
        throws Exception
    {
        if (reminderMgr==null)
        {
            reminderMgr = requireChild("reminders", ReminderMgr.class);
        }
        return reminderMgr;
    }


    @Override
    public NGRole getPrimaryRole() throws Exception {
        return getRequiredRole("Members");
    }
    @Override
    public NGRole getSecondaryRole() throws Exception {
        return getRequiredRole("Administrators");
    }

    /**
    * implemented special functionality for projects ... there are site
    * executives, and there are task assignees to consider.
    */
    @Override
    public boolean primaryOrSecondaryPermission(UserRef user) throws Exception {
        if (primaryPermission(user))
        {
            return true;
        }
        if (secondaryPermission(user))
        {
            return true;
        }
        NGRole execs = getSite().getRoleOrFail("Executives");
        if (execs.isPlayer(user))
        {
            return true;
        }
        //now walk through the action items, and check if person is assigned to any active goal

        for (GoalRecord gr : getAllGoals())
        {
            if (gr.isPassive()) {
                //ignore any passive action items that are from other projects.  Only consider local goals
                continue;
            }
            int state = gr.getState();
            if (state == BaseRecord.STATE_OFFERED ||
                state == BaseRecord.STATE_ACCEPTED||
                state == BaseRecord.STATE_WAITING )
            {
                if (gr.isAssignee(user))
                {
                    return true;
                }
            }
        }

        return false;
    }


    @Override
    public RoleRequestRecord createRoleRequest(String roleName, String requestedBy, long modifiedDate, String modifiedBy, String requestDescription) throws Exception
    {
        DOMFace rolelist = pageInfo.requireChild("Role-Requests", DOMFace.class);
        RoleRequestRecord newRoleRequest = rolelist.createChild("requests", RoleRequestRecord.class);
        newRoleRequest.setRequestId(generateKey());
        newRoleRequest.setModifiedDate(modifiedDate);
        newRoleRequest.setModifiedBy(modifiedBy);
        newRoleRequest.setState("Requested");
        newRoleRequest.setCompleted(false);
        newRoleRequest.setRoleName(roleName);
        newRoleRequest.setRequestedBy(requestedBy);
        newRoleRequest.setRequestDescription(requestDescription);
        newRoleRequest.setResponseDescription("");

        return newRoleRequest;
    }

    public synchronized static String generateKey() {
        return IdGenerator.generateKey();
    }

    @Override
    public List<RoleRequestRecord> getAllRoleRequest() throws Exception {

        List<RoleRequestRecord> requestList = new ArrayList<RoleRequestRecord>();
        DOMFace rolelist = pageInfo.requireChild("Role-Requests", DOMFace.class);
        Vector<RoleRequestRecord> children =  rolelist.getChildren("requests", RoleRequestRecord.class);
        for (RoleRequestRecord rrr: children) {
            requestList.add(rrr);
        }
        return requestList;
    }

    ///////////////// NOTES //////////////////////



    @Override
    public void saveContent(AuthRequest ar, String comment) throws Exception{
        saveFile( ar, comment );
    }


    @Override
    public String[] getContainerNames(){
        return getPageNames();
    }


    @Override
    public void setContainerNames(String[] nameSet) {
         setPageNames(nameSet);
    }


    /**
    * Used by ContainerCommon to provide methods for this class
    */
    @Override
    protected DOMFace getAttachmentParent() throws Exception {
        return getRequiredSection("Attachments");
    }

    /**
    * Used by ContainerCommon to provide methods for this class
    */
    @Override
    protected DOMFace getNoteParent() throws Exception {
        return getRequiredSection("Comments");
    }

    /**
    * Used by ContainerCommon to provide methods for this class
    */
    @Override
    protected DOMFace getRoleParent() throws Exception {
        if (pageInfo==null) {
            pageInfo = requireChild("pageInfo", PageInfoRecord.class);
        }
        return pageInfo.requireChild("roleList", DOMFace.class);
    }

    /**
    * Used by ContainerCommon to provide methods for this class
    */
    @Override
    protected DOMFace getHistoryParent() throws Exception {
        return getProcess().requireChild("history", DOMFace.class);
    }

    /**
    * Used by ContainerCommon to provide methods for this class
    */
    @Override
    protected DOMFace getInfoParent() throws Exception {
        if (pageInfo==null) {
            pageInfo = requireChild("pageInfo", PageInfoRecord.class);
        }
        return pageInfo;
    }


    @Override
    public void writeContainerLink(AuthRequest ar, int len) throws Exception
    {
        ar.write("<a href=\"");
        ar.write(ar.retPath);
        ar.write(ar.getDefaultURL(this));
        ar.write("\">");
        ar.writeHtml(trimName(getFullName(), len));
        ar.write( "</a>");
    }

    @Override
    public void writeDocumentLink(AuthRequest ar, String documentId, int len) throws Exception
    {
        AttachmentRecord att = findAttachmentByID(documentId);
        if(att==null)
        {
            ar.write( "(Document " );
            ar.write( documentId );
            ar.write( ")" );
            return;
        }
        String nameOfLink =  trimName(att.getDisplayName(), len);
        writePageUrl(ar);
        ar.write( "/docinfo");
        ar.writeURLData(documentId );
        ar.write( ".htm\">" );
        ar.writeHtml(nameOfLink);
        ar.write( "</a>");
    }

    @Override
    public void writeReminderLink(AuthRequest ar, String reminderId, int len) throws Exception
    {
        ReminderRecord att = getReminderMgr().findReminderByID( reminderId );
        if(att==null)
        {
            ar.write( "(Reminder " );
            ar.write( reminderId );
            ar.write( ")" );
            return;
        }
        String nameOfLink =  trimName(att.getFileDesc(), len);
        writePageUrl(ar);
        ar.write( "/sendemailReminder.htm?rid=" );
        ar.writeURLData(reminderId);
        ar.write( "'>" );
        ar.writeHtml(nameOfLink);
        ar.write( "</a>");
    }


    @Override
    public void writeTaskLink(AuthRequest ar, String taskId, int len) throws Exception
    {
        GoalRecord task = getGoalOrNull(taskId);
        if(task==null)
        {
            ar.write( "(Task " );
            ar.writeHtml( taskId );
            ar.write( ")" );
            return;
        }
        String nameOfLink = trimName(task.getSynopsis(), len);

        writePageUrl(ar);
        ar.write("/projectActiveTasks.htm\">" );
        ar.writeHtml(nameOfLink);
        ar.write( "</a>");
    }

    @Override
    public void writeNoteLink(AuthRequest ar,String noteId, int len) throws Exception{
        NoteRecord note = getNote( noteId );
        if(note==null){
            if ("x".equals(noteId))
            {
                ar.write("(attached documents only)");
            }
            else
            {
                ar.write( "(Topic " );
                ar.write( noteId );
                ar.write( ")" );
            }
            return;
        }
        String nameOfLink =  trimName(note.getSubject(), len);
        writePageUrl(ar);
        ar.write("/noteZoom");
        ar.writeURLData(note.getId());
        ar.write(".htm\">" );
        ar.writeHtml(nameOfLink);
        ar.write( "</a>");
    }


    private void writePageUrl(AuthRequest ar) throws Exception{
        ar.write( "<a href=\"" );
        ar.writeHtml(ar.baseURL );
        ar.write( "t/" );
        ar.writeHtml(getSiteKey());
        ar.write( "/" );
        ar.writeHtml(getKey());
    }


    public void deleteRoleRequest(String requestId) throws Exception {
        DOMFace roleRequests = pageInfo.requireChild("Role-Requests",
                DOMFace.class);
        Vector<Element> children = DOMUtils.getNamedChildrenVector( roleRequests.getElement(),
                "requests");
        for (Element child : children) {
            if ("requests".equals(child.getLocalName()) || "requests".equals(child.getNodeName())) {
                String childAttValue = child.getAttribute("id");
                if (childAttValue != null && requestId.equals(childAttValue)) {
                    roleRequests.getElement().removeChild(child);
                }
            }
        }
    }

    @Override
    public void setAllowPublic(String allowPublic)throws Exception{
        pageInfo.setAllowPublic(allowPublic);
    }

    @Override
    public String getAllowPublic() throws Exception{
        String value = pageInfo.getAllowPublic();
        if(value == null || value.length() == 0 ){
            value = "yes";
        }
        return value;
    }

    @Override
    public boolean isFrozen() throws Exception
    {
        return pageInfo.isFrozen();
    }
    public void freezeProject(AuthRequest ar)
    {
        pageInfo.freezeProject(ar);
    }

    public void unfreezeProject()
    {
        pageInfo.unfreezeProject();
    }
    public long getFrozenDate()
    {
        return pageInfo.getAttributeLong("freezeDate");
    }
    public String getFrozenUser()
    {
        return pageInfo.getAttribute("freezeUser");
    }



    public String getProjectMailId()
    {
        return pageInfo.getProjectMailId();
    }
    public void setProjectMailId(String id)
    {
        pageInfo.setProjectMailId(id);
    }

    /**
    * Different projects can have different style sheets (themes)
    */
    @Override
    public String getThemePath()
    {
        if (prjSite!=null) {
            return prjSite.getThemePath();
        }
        return "theme/blue/";
    }

    public void scanForNewFiles() throws Exception {
        // nothing in this class, this is overridden in the subclass NGProj
        // to look for new file that appeared in the project folder
    }

    public void removeExtrasByName(String name) throws Exception {
        // no extras in this class, this is overridden in the subclass NGProj
        // in order to clean up file in the folder that should not be there.
    }

    /**
     * Find all the tasks on this page, and assign them new, arbitrary
     * rank values such that they remain in the same order.
     * Assigned new values increasing by 10 so that other tasks can be place
     * between existing tasks if necessary.
     */
    public void renumberGoalRanks() throws Exception {
        int rankVal = 0;
        List<GoalRecord> allGoals = getAllGoals();
        GoalRecord.sortTasksByRank(allGoals);
        for (GoalRecord tr : allGoals) {
            String myParent = tr.getParentGoalId();
            // only renumber tasks that have no parent. Others renumbered
            // recursively
            if (myParent == null || myParent.length() == 0) {
                rankVal += 10;
                tr.setRank(rankVal);
                rankVal = renumberRankChildren(allGoals, rankVal, tr.getId());
            }
        }
    }

    private static int renumberRankChildren(List<GoalRecord> allGoals, int rankVal, String parentId)
            throws Exception {
        for (GoalRecord aGoal : allGoals) {
            if (parentId.equals(aGoal.getParentGoalId())) {
                rankVal += 10;
                aGoal.setRank(rankVal);
                rankVal = renumberRankChildren(allGoals, rankVal, aGoal.getId());
            }
        }
        return rankVal;
    }

    public File getContainingFolder() {
        return null;
    }


    public List<MeetingRecord> getMeetings() throws Exception {
        DOMFace meetings = requireChild("meetings", DOMFace.class);
        return meetings.getChildren("meeting", MeetingRecord.class);
    }
    public MeetingRecord findMeeting(String id) throws Exception {
        MeetingRecord m =findMeetingOrNull(id);
        if (m!=null) {
            return m;
        }
        throw new Exception("Could not find a meeting with the id="+id);
    }
    public MeetingRecord findMeetingOrNull(String id) throws Exception {
        for (MeetingRecord m : getMeetings()) {
            if (id.equals(m.getId())) {
                return m;
            }
        }
        return null;
    }
    public MeetingRecord createMeeting() throws Exception {
        DOMFace meetings = requireChild("meetings", DOMFace.class);
        MeetingRecord mr = meetings.createChildWithID("meeting", MeetingRecord.class, "id", this.getUniqueOnPage());
        mr.setState(1);
        return mr;
    }
    public void removeMeeting(String id) throws Exception {
        DOMFace meetings = requireChild("meetings", DOMFace.class);
        meetings.removeChildrenByNameAttrVal("meeting", "id", id);
    }

    public MeetingRecord getAgendaItemBacklog() throws Exception {
        for (MeetingRecord mr: getMeetings()) {
            if (mr.isBacklogContainer()) {
                return mr;
            }
        }
        //didn't find one, so create it now
        MeetingRecord newCont = createMeeting();
        newCont.setBacklogContainer(true);
        newCont.setName("BACKLOG AGENDA ITEM CONTAINER");
        return newCont;
    }



    public List<DecisionRecord> getDecisions() throws Exception {
        DOMFace meetings = requireChild("decisions", DOMFace.class);
        return meetings.getChildren("decision", DecisionRecord.class);
    }
    public DecisionRecord createDecision() throws Exception {
        DOMFace decisions = requireChild("decisions", DOMFace.class);
        int max = 0;
        for (DecisionRecord m : getDecisions()) {
            if (max < m.getNumber()) {
                max = m.getNumber();
            }
        }
        DecisionRecord dr = decisions.createChildWithID("decision", DecisionRecord.class,
                "num", Integer.toString(max+1));
        dr.setUniversalId(this.getContainerUniversalId()+"@DEC"+max);
        return dr;
    }
    public DecisionRecord findDecisionOrNull(int number) throws Exception {
        for (DecisionRecord m : getDecisions()) {
            if (number == m.getNumber()) {
                return m;
            }
        }
        return null;
    }
    public DecisionRecord findDecisionOrFail(int num) throws Exception {
        DecisionRecord dr = findDecisionOrNull(num);
        if (dr!=null) {
            return dr;
        }
        throw new Exception("Could not find a decision with the number="+num);
    }



    /**
    * Returns all the email generators for a project.
    */
    public List<EmailGenerator> getAllEmailGenerators() throws Exception {
        DOMFace generators =  requireChild("generators", DOMFace.class);
        return generators.getChildren("emailGenerator", EmailGenerator.class);
    }

    public JSONArray getJSONEmailGenerators(AuthRequest ar) throws Exception {
        JSONArray val = new JSONArray();
        List<EmailGenerator> gg = getAllEmailGenerators();
        int limit = 200;
        for (EmailGenerator egen : gg) {
            val.put(egen.getJSON(ar, this));
            if (limit--<0) {
                System.out.println("LIMIT: stopped including EmailGenerators at 200 out of "+gg.size());
                break;
            }
        }
        return val;
    }

    /**
    * Find the requested email generator, or throw an exception
    */
    public EmailGenerator getEmailGeneratorOrFail(String id) throws Exception {
        EmailGenerator egen = getEmailGeneratorOrNull(id);
        if (egen==null) {
            throw new Exception("Not able to find an Email Generator with the ID="+id);
        }
        return egen;
    }

    public EmailGenerator getEmailGeneratorOrNull(String id) throws Exception {
        for (EmailGenerator egen : getAllEmailGenerators()) {
            if (id.equals(egen.getId())) {
                return egen;
            }
        }
        return null;
    }

    public EmailGenerator createEmailGenerator() throws Exception {
        DOMFace meetings = requireChild("generators", DOMFace.class);
        EmailGenerator egen = meetings.createChildWithID("emailGenerator", EmailGenerator.class, "id", this.getUniqueOnPage());
        return egen;
    }


    /**
     * Walk through all the references and make a list of all the people that are
     * mentioned at any point in the project, and returns the AddressListEntry
     * for each
     */
    public List<AddressListEntry> getAllAddressesInProject() throws Exception {
        HashSet<String> nameSet = new HashSet<String>();
        Vector<AddressListEntry> result = new Vector<AddressListEntry>();
        for (NGRole role : getAllRoles()) {
            for (AddressListEntry ale : role.getExpandedPlayers(this)) {
                String uid = ale.getUniversalId();
                if (!nameSet.contains(uid)) {
                    nameSet.add(uid);
                    result.add(ale);
                }
            }
        }
        return result;
    }


    /**
     * Walk through all the references and make a list of all the people that are
     * mentioned at any point in the project, and returns the combined address
     * that has a name and an email address like this:
     *
     *      John Smith <jsmith@example.com>
     *
     */
    public JSONArray getAllPeopleInProject() throws Exception {
        List<AddressListEntry> nameSet = getAllAddressesInProject();
        JSONArray list = new JSONArray();
        for (AddressListEntry ale : nameSet) {
            list.put(ale.generateCombinedAddress());
        }
        return list;
    }


    /**
    * Returns all the labels for a project, including all the
    * roles as well as the non-role labels
    */
    public List<NGLabel> getAllLabels() throws Exception {
        Vector<NGLabel> ret = new Vector<NGLabel>();
        DOMFace labelList =  requireChild("labelList", DOMFace.class);
        for (LabelRecord lr : labelList.getChildren("label", LabelRecord.class)) {
            ret.add(lr);
        }
        for (NGRole aRole : this.getAllRoles()) {
            ret.add(aRole);
        }
        return ret;
    }

    public JSONArray getJSONLabels() throws Exception {
        JSONArray val = new JSONArray();
        for (NGLabel egen : getAllLabels()) {
            val.put(egen.getJSON());
        }
        return val;
    }

    /**
    * Find the requested label record, or throw an exception
    */
    public NGLabel getLabelRecordOrFail(String name) throws Exception {
        NGLabel label = getLabelRecordOrNull(name);
        if (label==null) {
            throw new Exception("Not able to find a Label Record with the name="+name);
        }
        return label;
    }

    public NGLabel getLabelRecordOrNull(String name) throws Exception {
        for (NGLabel egen : getAllLabels()) {
            if (name.equals(egen.getName())) {
                return egen;
            }
        }
        return null;
    }

    public NGLabel findOrCreateLabelRecord(String name) throws Exception {
        NGLabel label = getLabelRecordOrNull(name);
        if (label==null) {
            DOMFace labelList = requireChild("labelList", DOMFace.class);
            label = labelList.createChildWithID("label", LabelRecord.class, "name", name);
        }
        return label;
    }

    /**
     * Only removes the "pure label" style of label
     */
    public void removeLabelRecord(LabelRecord existing) throws Exception {
        DOMFace labelList = requireChild("labelList", DOMFace.class);
        labelList.removeChild(existing);
    }


    ///////////////////// PARENT PROJECT /////////////////////


    public String getParentKey() throws Exception {
        return getInfoParent().getScalar("parentProject");
    }

    public void setParentKey(String parentKey) throws Exception {
        getInfoParent().setScalar("parentProject", parentKey);
    }


    /**
     * This will delete all email records in the project (workspace)
     */
    public void clearAllEmail() throws Exception {
        DOMFace mail = requireChild("mail", DOMFace.class);
        mail.clearVector("email");
    }


    /**
     * Return the time of the next automated action.  If there are multiple
     * scheduled actions, this returns the time of the next one.
     *
     * A negative number means there are no scheduled events.
     */
    public long nextActionDue() throws Exception {
        //initialize to some time next year
        long nextTime = System.currentTimeMillis() + 31000000000L;
        for (EmailRecord er : getAllEmail()) {
            if (er.statusReadyToSend()) {
                //there is no scheduled time for sending email .. it just is scheduled
                //immediately and supposed to be sent as soon as possible after that
                //so return now minus 1 minutes
                long reminderTime = System.currentTimeMillis()-60000;
                if (reminderTime < nextTime) {
                    System.out.println("Workspace has email that needs to be collected");
                    nextTime = reminderTime;
                }
            }
        }

        ArrayList<ScheduledNotification> resList = new ArrayList<ScheduledNotification>();
        gatherUnsentScheduledNotification(resList);

        ScheduledNotification first = null;
        //Now scan all the standard scheduled notifications
        for (ScheduledNotification sn : resList) {
            long timeToAct = sn.timeToSend();
            if (timeToAct < nextTime) {
                nextTime = timeToAct;
                first = sn;
            }
        }
        if (first!=null) {
            System.out.println("Found the next event to be: "+first.selfDescription()+" at "
                     +new Date(first.timeToSend()));
            if (first.isSent()) {
                System.out.println("BUT it was sent!");
            }
        }
        return nextTime;
    }


    public void gatherUnsentScheduledNotification(ArrayList<ScheduledNotification> resList) throws Exception {
        for (MeetingRecord meeting : getMeetings()) {
            meeting.gatherUnsentScheduledNotification(this, resList);
        }
        for (NoteRecord note : this.getAllNotes()) {
            note.gatherUnsentScheduledNotification(this, resList);
        }
        for (EmailGenerator eg : getAllEmailGenerators()) {
            eg.gatherUnsentScheduledNotification(this, resList);
        }
        for (GoalRecord goal : getAllGoals()) {
            goal.gatherUnsentScheduledNotification(this, resList);
        }
    }

    /**
     * Acts on and performs a SINGLE scheduled action that is scheduled to be done
     * before the current time.  Actions are done one at a time so that the calling
     * code can decide to save the page before calling to execute the next action,
     * or to spread a large number of actions out a bit.
     *
     * This should ONLY be called on the background email thread.
     */
    public void performScheduledAction(AuthRequest ar, MailFile mailFile) throws Exception {

        ArrayList<ScheduledNotification> resList = new ArrayList<ScheduledNotification>();
        gatherUnsentScheduledNotification(resList);
        ScheduledNotification earliest = null;
        long nextTime = System.currentTimeMillis() + 31000000000L;

        for (ScheduledNotification sn : resList) {
            if (sn.timeToSend() < nextTime) {
                earliest = sn;
                nextTime = sn.timeToSend();
            }
        }

        if (earliest!=null) {
            earliest.sendIt(ar, mailFile);
            System.out.println("BACKGROUND: Just sent: "+earliest.selfDescription());
            return;   //only one thing at a time
        }
    }

    public JSONObject getConfigJSON() throws Exception {
        ProcessRecord process = getProcess();
        JSONObject projectInfo = new JSONObject();
        projectInfo.put("goal", process.getSynopsis());
        projectInfo.put("purpose", process.getDescription());
        projectInfo.put("parentKey", getParentKey());
        projectInfo.put("allowPublic", "yes".equals(getAllowPublic()));
        projectInfo.put("frozen", isFrozen());
        projectInfo.put("deleted", isDeleted());
        projectInfo.put("upstream", getUpstreamLink());
        projectInfo.put("projectMail", getProjectMailId());
        return projectInfo;
    }

    public void updateConfigJSON(AuthRequest ar, JSONObject newConfig) throws Exception {
        ProcessRecord process = getProcess();
        if (newConfig.has("goal")) {
            process.setSynopsis(newConfig.getString("goal"));
        }
        if (newConfig.has("purpose")) {
            process.setDescription(newConfig.getString("purpose"));
        }
        if (newConfig.has("parentKey")) {
            setParentKey(newConfig.getString("parentKey"));
        }
        if (newConfig.has("allowPublic")) {
            if (newConfig.getBoolean("allowPublic")) {
                setAllowPublic("yes");
            }
            else {
                setAllowPublic("no");
            }
        }
        if (newConfig.has("deleted")) {
            boolean newDelete = newConfig.getBoolean("deleted");
            if (newDelete != isDeleted()) {
                if (newDelete) {
                    markDeleted(ar);
                }
                else {
                    markUnDeleted(ar);
                }
            }
        }
        if (newConfig.has("frozen")) {
            boolean newFrozen = newConfig.getBoolean("frozen");
            if (newFrozen != isFrozen()) {
                if (newFrozen) {
                    freezeProject(ar);
                }
                else {
                    unfreezeProject();
                }
            }
        }
        if (newConfig.has("upstream")) {
            setUpstreamLink(newConfig.getString("upstream"));
        }
        if (newConfig.has("projectMail")) {
            setProjectMailId(newConfig.getString("projectMail"));
        }
    }


}
