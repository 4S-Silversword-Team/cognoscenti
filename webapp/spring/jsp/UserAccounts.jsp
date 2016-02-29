<%@page errorPage="/spring/jsp/error.jsp"
%><%@ include file="/spring/jsp/include.jsp"
%><%@page import="org.socialbiz.cog.SiteRequest"
%><%@page import="org.socialbiz.cog.SiteReqFile"
%><%@page import="org.socialbiz.cog.WorkspaceStats"
%><%
    ar.assertLoggedIn("Must be logged in to see anything about a user");

    UserProfile uProf = (UserProfile)request.getAttribute("userProfile");
    if (uProf == null) {
        throw new NGException("nugen.exception.cant.find.user",null);
    }

    UserProfile  operatingUser =ar.getUserProfile();
    if (operatingUser==null) {
        //this should never happen, and if it does it is not the users fault
        throw new ProgramLogicError("user profile setting is null.  No one appears to be logged in.");
    }

    boolean viewingSelf = uProf.getKey().equals(operatingUser.getKey());

    JSONArray siteList = new JSONArray();
    for (NGBook site : uProf.findAllMemberSites()) {
        JSONObject jObj = new JSONObject();
        jObj.put("siteKey",  site.getKey());
        jObj.put("siteDesc", site.getDescription());
        jObj.put("name",     site.getFullName());
        WorkspaceStats stats = site.getRecentStats(ar.getCogInstance());
        jObj.put("numWorkspaces", stats.numWorkspaces);
        jObj.put("numTopics", stats.numTopics);
        siteList.put(jObj);
    }

    JSONArray requestList = new JSONArray();
    JSONArray superList = new JSONArray();

    boolean isSuper = ar.isSuperAdmin();
    List<SiteRequest> allRequests = SiteReqFile.getAllSiteReqs();
    for (SiteRequest oneRequest: allRequests) {
        JSONObject jObj = new JSONObject();
        jObj.put("name",    oneRequest.getName());
        jObj.put("desc",    oneRequest.getDescription());
        jObj.put("status",  oneRequest.getStatus());
        jObj.put("siteKey", oneRequest.getSiteId());
        jObj.put("date",    oneRequest.getModTime());
        if(uProf.hasAnyId(oneRequest.getUniversalId())) {
            requestList.put(jObj);
        }
        if (isSuper && oneRequest.getStatus().equalsIgnoreCase("requested")) {
            superList.put(jObj);
        }
    }
%>

<script type="text/javascript">

var app = angular.module('myApp', ['ui.bootstrap']);
app.controller('myCtrl', function($scope, $http) {
    $scope.siteList = <%siteList.write(out,2,4);%>;
    $scope.requestList = <%requestList.write(out,2,4);%>;
    $scope.superList = <%superList.write(out,2,4);%>;
    $scope.filter = "";
    $scope.reqNum = <%=requestList.length()%>;

    $scope.showInput = false;
    $scope.showError = false;
    $scope.errorMsg = "";
    $scope.errorTrace = "";
    $scope.showTrace = false;
    $scope.reportError = function(serverErr) {
        errorPanelHandler($scope, serverErr);
    };

});

</script>


<!-- MAIN CONTENT SECTION START -->
<div ng-app="myApp" ng-controller="myCtrl">

<%@include file="ErrorPanel.jsp"%>

    <div class="generalHeading" style="height:40px">
        <div  style="float:left;margin-top:8px;">
            List of Sites
        </div>
        <div class="rightDivContent" style="margin-right:100px;">
          <span class="dropdown">
            <button class="btn btn-default dropdown-toggle" type="button" id="menu1" data-toggle="dropdown">
            Options: <span class="caret"></span></button>
            <ul class="dropdown-menu" role="menu" aria-labelledby="menu1">
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  href="requestAccount.htm">Request New Site</a></li>
            </ul>
          </span>

        </div>
    </div>

        <div class="generalContent">
            <table class="gridTable2" width="100%">
                <tr class="gridTableHeader">
                    <td>Site Name</td>
                    <td>Site Description</td>
                    <td>Workspaces</td>
                    <td>Topics</td>
                </tr>
                <tr ng-repeat="rec in siteList">
                    <td>
                        <a href="<%=ar.retPath%>t/{{rec.siteKey}}/$/accountListProjects.htm" title="navigate to the site">{{rec.name}}</a>
                    </td>
                    <td>{{rec.siteDesc}}</td>
                    <td>{{rec.numWorkspaces}}</td>
                    <td>{{rec.numTopics}}</td>
                </tr>
            </table>

        </div>

        <div class="guideVocal" ng-show="siteList.length==0">
            <p ng-show="reqNum>0">User <% uProf.writeLink(ar); %> has requested {{reqNum}} sites.</p>
            <p ng-show="reqNum==0"><b>User <% uProf.writeLink(ar); %> does not have any sites.</b></p>
            <p>A site is required if you want to create your own projects in your own space.  Every workspace belongs to one site.</p>

            <p>You do not need an site in order to participate on projects that have already been created.<br/>
            Other site owners may give you permission to create projects in their sites.</p>

            <form name="createAccountForm" method="GET" action="requestAccount.htm">
                <input type="submit" class="btn btn-sm"  Value="Request New Site">
            </form>
            <p>Use this button to request an site from the system administrator.</p>

            <p>If approved, you will be able to create your own new projects in your site, <br/>
            and you will be able to authorize others to create projects in your site.</p>

        </div>

        <div class="generalHeadingBorderLess" ng-show="requestList.length>0"><br/>Status of Site Requests</div>
        <div class="generalContent" ng-show="requestList.length>0">
            <div id="accountRequestPaging"></div>
            <div id="accountRequestDiv">
                <table class="gridTable2" width="100%">
                    <tr class="gridTableHeader">
                        <td>Proposed Name</td>
                        <td>Description</td>
                        <td>Status</td>
                        <td>Date</td>
                    </tr>
                    <tr ng-repeat="rec in requestList">
                        <td>
                            <span ng-show="rec.status=='Granted'">
                                <a href="<%=ar.retPath%>t/{{rec.siteKey}}/$/accountListProjects.htm">{{rec.name}}</a>
                            </span>
                            <span ng-hide="rec.status=='Granted'">{{rec.name}}</span>
                        </td>
                        <td>{{rec.desc}}</td>
                        <td>{{rec.status}}</td>
                        <td>{{rec.date | date}}</td>
                    </tr>
                </table>
            </div>
        </div>

    </div>
</div>

