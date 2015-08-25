<%@page errorPage="/spring/jsp/error.jsp"
%><%@ include file="UserProfile.jsp"
%><%

    UserProfile uProf = (UserProfile)request.getAttribute("userProfile");
    if (uProf == null) {
        throw new NGException("nugen.exception.cant.find.user",null);
    }

    UserProfile  operatingUser =null;
    boolean viewingSelf = false;
    if (ar.hasSpecialSessionAccess("Notifications:"+uProf.getKey())) {
        operatingUser = uProf;
        viewingSelf = true;
    }
    else {
        ar.assertLoggedIn("Must be logged in to see anything about user "+uProf.getKey());
        operatingUser =ar.getUserProfile();
        viewingSelf = uProf.getKey().equals(operatingUser.getKey());
    }

    if (operatingUser==null) {
        //this should never happen, and if it does it is not the users fault
        throw new ProgramLogicError("user profile setting is null.  No one appears to be logged in.");
    }

    JSONArray partProjects = new JSONArray();
    Vector<NGPageIndex> v = ar.getCogInstance().getProjectsUserIsPartOf(uProf);
    for(NGPageIndex ngpi : v){
        NGPage ngp = ngpi.getPage();
        JSONObject jo = new JSONObject();
        jo.put("key", ngp.getKey());
        jo.put("fullName", ngp.getFullName());
        partProjects.put(jo);
    }

%>

<script type="text/javascript">

var app = angular.module('myApp', ['ui.bootstrap']);
app.controller('myCtrl', function($scope, $http) {
    $scope.partProjects = <%partProjects.write(out,2,4);%>;



    $scope.showError = false;
    $scope.errorMsg = "";
    $scope.errorTrace = "";
    $scope.showTrace = false;
    $scope.reportError = function(serverErr) {
        errorPanelHandler($scope, serverErr);
    };

    $scope.noImpl = function() {
        alert('no implemented yet');
    }

});
</script>



<!-- MAIN CONTENT SECTION START -->
<div ng-app="myApp" ng-controller="myCtrl">

<%@include file="ErrorPanel.jsp"%>

    <div class="generalHeading" style="height:40px">
        <div  style="float:left;margin-top:8px;">
            Unsubscribe from Notification
        </div>
        <div class="rightDivContent" style="margin-right:100px;">
          <span class="dropdown">
            <button class="btn btn-default dropdown-toggle" type="button" id="menu1" data-toggle="dropdown">
            Options: <span class="caret"></span></button>
            <ul class="dropdown-menu" role="menu" aria-labelledby="menu1">
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  href="" ng-click="noImpl()">Do Nothing</a></li>
            </ul>
          </span>

        </div>
    </div>


<div ng-repeat="prj in partProjects" style="border: 1px solid lightgrey;border-radius:10px;margin-top:20px;padding:5px;background-color:#F8EEEE;">
    <span class="dropdown">
        <button class="btn btn-default dropdown-toggle" type="button" id="menu1" data-toggle="dropdown">
            <span class="caret"></span></button>
        <ul class="dropdown-menu" role="menu" aria-labelledby="menu1">
            <li role="presentation">
                <a role="menuitem" tabindex="-1" ng-click="noImpl()">Stop Being a Member</a></li>
        </ul>
    </span>
    <b>{{prj.fullName}}</b> - {{prj.key}}
    <div style="background-color:white;border-radius:5px;margin:5px;">
        details appear here
    </div>
</div>



