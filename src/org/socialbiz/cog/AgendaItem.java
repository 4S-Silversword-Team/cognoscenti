package org.socialbiz.cog;

import java.util.ArrayList;
import java.util.List;

import org.socialbiz.cog.mail.ScheduledNotification;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.workcast.json.JSONArray;
import org.workcast.json.JSONObject;

public class AgendaItem extends DOMFace {

    public static final int STATUS_GOOD = 1;
    public static final int STATUS_MID  = 2;
    public static final int STATUS_POOR = 3;


    public AgendaItem(Document doc, Element ele, DOMFace p) {
        super(doc, ele, p);
        //check if the lock needs to be cleared after being idle
        //for 30 minutes
        long lockTime = getLockTime();
        if (lockTime>0 && lockTime < System.currentTimeMillis()-30*60000) {
            clearLock();
        }
    }
    

    public String getId()  throws Exception {
        return getAttribute("id");
    }

    public String getSubject()  throws Exception {
        return getScalar("subject");
    }
    public void setSubject(String newVal) throws Exception {
        setScalar("subject", newVal);
    }

    public String getDesc()  throws Exception {
        return getScalar("desc");
    }
    public void setDesc(String newVal) throws Exception {
        setScalar("desc", newVal);
    }

    public long getDuration()  throws Exception {
        return safeConvertLong(getAttribute("duration"));
    }
    public void setDuration(long newVal) throws Exception {
        setAttribute("duration", Long.toString(newVal));
    }

    /**
     * This value represents the order of the agenda items in the meeting
     * Lower values are before higher values.
     */
    public int getPosition() {
        return getAttributeInt("position");
    }
    public void setPosition(int newVal) throws Exception {
        setAttributeInt("position", newVal);
    }

    /**
     * An agenda item can be linked to a discussion topic
     */
    public String getTopicLink() {
        return getAttribute("topicLink");
    }
    public void setTopicLink(String newVal) throws Exception {
        setAttribute("topicLink", newVal);
    }
    
    public String getNotes()  throws Exception {
        return getScalar("notes");
    }
    public void setNotes(String newVal) throws Exception {
        setScalar("notes", newVal);
    }

    public int getStatus() {
        return getAttributeInt("status");
    }
    public void setStatus(int newVal) throws Exception {
        setAttributeInt("status", newVal);
    }

    public boolean getReadyToGo() {
        return getAttributeBool("readyToGo");
    }
    public void setReadyToGo(boolean newVal) throws Exception {
        setAttributeBool("readyToGo", newVal);
    }


    public List<String> getActionItems()  throws Exception {
        return getVector("actionId");
    }
    public void addActionItemId(String goalId)  throws Exception {
        this.addVectorValue("actionId", goalId);
    }
    public void setActionItems(List<String> newVal) throws Exception {
        setVector("actionId", newVal);
    }

    public List<String> getDocList()  throws Exception {
        return getVector("docList");
    }
    public void addDocId(String goalId)  throws Exception {
        this.addVectorValue("docList", goalId);
    }
    public void setDocList(List<String> newVal) throws Exception {
        setVector("docList", newVal);
    }

    public List<String> getPresenters()  throws Exception {
        return getVector("presenters");
    }
    public void setPresenters(List<String> newVal) throws Exception {
        setVector("presenters", newVal);
    }

    public List<CommentRecord> getComments()  throws Exception {
        return getChildren("comment", CommentRecord.class);
    }
    public CommentRecord getComment(long timeStamp)  throws Exception {
        for (CommentRecord cr : getComments()) {
            if (timeStamp == cr.getTime()) {
                return cr;
            }
        }
        return null;
    }
    public CommentRecord addComment(AuthRequest ar)  throws Exception {
        CommentRecord newCR = createChild("comment", CommentRecord.class);
        newCR.setTime(ar.nowTime);
        newCR.setUser(ar.getUserProfile());
        return newCR;
    }
    public void deleteComment(long timeStamp)  throws Exception {
        CommentRecord selectedForDelete = findComment(timeStamp);
        if (selectedForDelete!=null) {
            this.removeChild(selectedForDelete);
        }
    }

    /**
     * This is the edit lock mechanism.  Before making a change the
     * client should get an edit lock on this object.  It records the user
     * and the time that the lock was made.  Locks are automatically cleared
     * 30 minutes after setting them ... nobody can hold a lock that long.
     * When a save is made, the lock should be cleared.
     * The current lock owner is communicated to the client, for two reasons:
     * 1. this is the flag that opens the editor
     * 2. this tells others that the object is currently being edited.
     *
     * Rules:
     * 1. get the lock before allowing the user to edit
     * 2. If you try to get the lock, but find out it is someone else,
     *    then display that it is being edited by that other person
     * 3. When you save the lock will be cleared, so you have to get it again.
     * 4. If the user cancels edit, be sure to clear the lock.
     *
     */
    public AddressListEntry getLockUser() {
        String user = getScalar("editUser");
        if (user==null || user.length()==0) {
            return null;
        }
        return new AddressListEntry(user);
    }
    public long getLockTime() {
        return safeConvertLong( getScalar("editTime"));
    }
    public void setLock(UserRef ur, long time) {
        setScalar("editUser", ur.getUniversalId());
        setScalar("editTime", Long.toString(time));
    }
    public void clearLock() {
        setScalar("editUser", null);
        setScalar("editTime", null);
    }

    /**
     * A small object suitable for lists of agenda items
     */
    public JSONObject getJSON(AuthRequest ar) throws Exception {
        JSONObject aiInfo = new JSONObject();
        aiInfo.put("id",        getId());
        aiInfo.put("subject",   getSubject());
        aiInfo.put("duration",  getDuration());
        aiInfo.put("status",    getStatus());
        aiInfo.put("topicLink", getTopicLink());
        aiInfo.put("readyToGo", getReadyToGo());
        String htmlVal = WikiConverterForWYSIWYG.makeHtmlString(ar, getDesc());
        aiInfo.put("desc",      htmlVal);
        aiInfo.put("position",  getPosition());
        htmlVal = WikiConverterForWYSIWYG.makeHtmlString(ar, getNotes());
        aiInfo.put("notes",     htmlVal);
        aiInfo.put("actionItems", constructJSONArray(getActionItems()));
        aiInfo.put("docList", constructJSONArray(getDocList()));
        aiInfo.put("presenters", constructJSONArray(getPresenters()));

        JSONArray allCommentss = new JSONArray();
        for (CommentRecord cr : getComments()) {
            allCommentss.put(cr.getHtmlJSON(ar));
        }
        aiInfo.put("comments",  allCommentss);
        AddressListEntry locker = getLockUser();
        if (locker!=null) {
            aiInfo.put("lockUser",  locker.getJSON());
        }
        return aiInfo;
    }
    private CommentRecord findComment(long timeStamp) throws Exception {
        for (CommentRecord cr : this.getComments()) {
            if (cr.getTime() == timeStamp) {
                return cr;
            }
        }
        return null;
    }


    public void updateFromJSON(AuthRequest ar, JSONObject input) throws Exception {
        if (input.has("subject")) {
            setSubject(input.getString("subject"));
        }
        if (input.has("duration")) {
            setDuration(input.getLong("duration"));
        }
        if (input.has("desc")) {
            String html = input.getString("desc");
            setDesc(HtmlToWikiConverter.htmlToWiki(ar.baseURL, html));
        }
        if (input.has("position")) {
            setPosition(input.getInt("position"));
        }
        if (input.has("status")) {
            setStatus(input.getInt("status"));
        }
        if (input.has("readyToGo")) {
            setReadyToGo(input.getBoolean("readyToGo"));
        }
        
        if (input.has("notes")) {
            String html = input.getString("notes");
            setNotes(HtmlToWikiConverter.htmlToWiki(ar.baseURL, html));
        }
        if (input.has("topicLink")) {
            String topicLink = input.getString("topicLink");
            setTopicLink(topicLink);
        }
        if (input.has("actionItems")) {
            setActionItems(constructVector(input.getJSONArray("actionItems")));
        }
        if (input.has("docList")) {
            setDocList(constructVector(input.getJSONArray("docList")));
        }
        if (input.has("presenters")) {
            setPresenters(constructVector(input.getJSONArray("presenters")));
        }
        if (input.has("comments")) {
            JSONArray comments = input.getJSONArray("comments");
            for (int i=0; i<comments.length(); i++) {
                JSONObject cmt = comments.getJSONObject(i);
                long timeStamp = cmt.getLong("time");
                //timeStamp of 0 or -1 means to create it
                if (timeStamp<=0) {
                    CommentRecord cr = addComment(ar);
                    cr.updateFromJSON(cmt, ar);
                    if (cmt.has("replyTo")) {
                        long replyToValue = cmt.getLong("replyTo");
                        CommentRecord source = this.findComment(replyToValue);
                        if (source!=null) {
                            source.addOneToReplies(cr.getTime());
                        }
                        else {
                            System.out.println("New comment reply to time value, cannot find corresponding comment: "+replyToValue);
                        }
                    }
                }
                else if (cmt.has("deleteMe")) {
                    //a special flag in the comment indicates it should be removed
                    deleteComment(timeStamp);
                }
                else {
                    CommentRecord cr = getComment(timeStamp);
                    if (cr!=null) {
                        cr.updateFromJSON(cmt, ar);
                    }
                }
            }
        }
        if (input.has("setLock")) {
            AddressListEntry currentLocker = getLockUser();
            if (currentLocker==null) {
                setLock(ar.getUserProfile(), ar.nowTime);
            }
        }
        if (input.has("clearLock")) {
            AddressListEntry currentLocker = getLockUser();
            if (currentLocker!=null && ar.getUserProfile().equals(currentLocker)) {
                clearLock();
            }
        }

    }

    public void gatherUnsentScheduledNotification(NGPage ngp, EmailContext meet, ArrayList<ScheduledNotification> resList) throws Exception {
        for (CommentRecord ac : this.getComments()) {
            ac.gatherUnsentScheduledNotification(ngp, meet, resList);
        }
    }


}
