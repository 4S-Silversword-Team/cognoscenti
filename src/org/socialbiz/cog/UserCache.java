package org.socialbiz.cog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

import org.workcast.json.JSONArray;
import org.workcast.json.JSONObject;

public class UserCache {
    JSONObject cacheObj;
    String userKey;
    File userCacheFile;

    UserCache(Cognoscenti cog, String _userKey) throws Exception {
        userKey = _userKey;
        File userFolder = cog.getConfig().getUserFolderOrFail();
        userCacheFile = new File(userFolder, userKey+".user.json");
        if (userCacheFile.exists()) {
            cacheObj = JSONObject.readFromFile(userCacheFile);
        }
        else {
            cacheObj = new JSONObject();
            save();
        }
    }

    public void save() throws Exception {
        File folder = userCacheFile.getParentFile();
        File tempFile = new File(folder, "~"+userCacheFile.getName()+"~tmp~");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        FileOutputStream fos = new FileOutputStream(tempFile);
        OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
        cacheObj.write(osw,2,0);
        osw.close();
        if (userCacheFile.exists()) {
            userCacheFile.delete();
        }
        tempFile.renameTo(userCacheFile);
    }

    // operation get task list.
    public void refreshCache(Cognoscenti cog) throws Exception {

        NGPageIndex.assertNoLocksOnThread();
        long nowTime= System.currentTimeMillis();

        JSONArray actionItemList = new JSONArray();
        JSONArray proposalList = new JSONArray();
        JSONArray openRounds = new JSONArray();

        UserProfile up = UserManager.getUserProfileByKey(userKey);

        for (NGPageIndex ngpi : cog.getAllContainers()) {
            if (!ngpi.isProject()) {
                continue;
            }

            NGPage aPage = ngpi.getPage();
            for (GoalRecord gr : aPage.getAllGoals()) {

                if (gr.isPassive()) {
                    //ignore tasks that are from other servers.  They will be identified and tracked on
                    //those other servers
                    continue;
                }

                if (!gr.isAssignee(up)) {
                    continue;
                }

                actionItemList.put(gr.getJSON4Goal(aPage));
            }
            for (NoteRecord aNote : aPage.getAllNotes()) {
                String targetRoleName = aNote.getTargetRole();
                NGRole targetRole = aPage.getRole(targetRoleName);
                if (targetRole==null) {
                    //ignore notes that have invalid target role
                    continue;
                }
                if (!targetRole.isPlayer(up)) {
                    //ignore notes that this user is not a player of target role
                    continue;
                }
                String address = "noteZoom"+aNote.getId()+".htm";
                for (CommentRecord cr : aNote.getComments()) {
                    addPollIfNoResponse(proposalList, openRounds, cr, up, aPage, address, nowTime);
                }
            }
            for (MeetingRecord meet : aPage.getMeetings()) {
                String targetRoleName = meet.getTargetRole();
                NGRole targetRole = aPage.getRole(targetRoleName);
                if (targetRole==null) {
                    //ignore notes that have invalid target role
                    continue;
                }
                if (!targetRole.isPlayer(up)) {
                    //ignore notes that this user is not a player of target role
                    continue;
                }
                String address = "meetingFull.htm?id="+meet.getId();
                for (AgendaItem ai : meet.getAgendaItems()) {
                    for (CommentRecord cr : ai.getComments()) {
                        addPollIfNoResponse(proposalList, openRounds, cr, up, aPage, address, nowTime);
                    }
                }
            }

            
            
            // clean out any outstanding locks in every loop
            NGPageIndex.clearLocksHeldByThisThread();
       }

        cacheObj.put("actionItems", actionItemList);
        cacheObj.put("proposals", proposalList);
        cacheObj.put("openRounds", openRounds);
    }
    
    private void addPollIfNoResponse(JSONArray proposalList, JSONArray openRounds, CommentRecord cr,
            UserProfile up, NGPage aPage, String address, long nowTime) throws Exception {
        if (cr.getCommentType()>CommentRecord.COMMENT_TYPE_SIMPLE &&
                cr.getState()==CommentRecord.COMMENT_STATE_OPEN) {
            ResponseRecord rr = cr.getResponse(up);
            if (rr==null) {
                //add proposal info if there is no response from this user
                //seems a bit overkill to have everything, but then,
                //everything is there for displaying a list...
                addRecordToList(proposalList, cr,  aPage, address);
            }
            if (up.equals(cr.getUser())) {
                //If user created this round, then remember that ... because it is still open
                addRecordToList(openRounds, cr,  aPage, address);
            }
        }
    }
    
    private void addRecordToList(JSONArray list, CommentRecord cr, NGPage aPage, String address) throws Exception {
        JSONObject jo = cr.getJSON();
        String prop = cr.getContent();
        if (prop.length()>100) {
            prop = prop.substring(0,100);
        }
        jo.put("content", prop);
        jo.put("workspaceKey", aPage.getKey());
        jo.put("workspaceName", aPage.getFullName());
        NGBook site = aPage.getSite();
        jo.put("siteKey", site.getKey());
        jo.put("siteName", site.getFullName());
        jo.put("address", address+"#cmt"+cr.getTime());
        list.put(jo);
    }

    public JSONArray getActionItems() throws Exception {
        //TODO: somehow this can be in a state of not being initialized.
        //this test prevents a problem, but still wonder why it exists on new users
        if (cacheObj.has("actionItems")) {
            return cacheObj.getJSONArray("actionItems");
        }
        return new JSONArray();
    }
    public JSONArray getProposals() throws Exception {
        //TODO: somehow this can be in a state of not being initialized.
        //this test prevents a problem, but still wonder why it exists on new users
        if (cacheObj.has("proposals")) {
            return cacheObj.getJSONArray("proposals");
        }
        return new JSONArray();
    }
    
    /**
     * Get a list of the comments (proposals and rounds) that still open
     * but not yet closed, whether overdue or not.
     */
    public JSONArray getOpenRounds() throws Exception {
        if (cacheObj.has("openRounds")) {
            return cacheObj.getJSONArray("openRounds");
        }
        return new JSONArray();
    }
}
