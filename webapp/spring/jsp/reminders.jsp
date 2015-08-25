<%@page errorPage="/spring/jsp/error.jsp"
%><%@ include file="/spring/jsp/attachment_forms.jsp"
%><%

    String encodedLoginMsg = URLEncoder.encode("Can't open form","UTF-8");

    ReminderMgr rMgr = ngp.getReminderMgr();
    JSONArray allReminders = new JSONArray();
    for (ReminderRecord rRec : rMgr.getAllReminders()) {
        JSONObject jo = new JSONObject();
        jo.put("id", rRec.getId());
        jo.put("subject", rRec.getSubject());
        jo.put("assignee", rRec.getAssignee());
        jo.put("instructions", rRec.getInstructions());
        jo.put("fileName", rRec.getFileName());
        jo.put("fileDesc", rRec.getFileDesc());
        jo.put("destFolder", rRec.getDestFolder());
        jo.put("isOpen", rRec.isOpen());
        allReminders.put(jo);
    }

%>


<script type="text/javascript">

var app = angular.module('myApp', ['ui.bootstrap']);
app.controller('myCtrl', function($scope, $http) {
    $scope.allReminders = <%allReminders.write(out,2,4);%>;

    $scope.showError = false;
    $scope.errorMsg = "";
    $scope.errorTrace = "";
    $scope.showTrace = false;
    $scope.reportError = function(serverErr) {
        errorPanelHandler($scope, serverErr);
    };

    $scope.noImpl = function() {
        alert("Not implemented yet");
    }
});
</script>

<div ng-app="myApp" ng-controller="myCtrl">

<%@include file="ErrorPanel.jsp"%>

    <div class="generalHeading" style="height:40px">
        <div  style="float:left;margin-top:8px;">
            Document Reminders
        </div>
        <div class="rightDivContent" style="margin-right:100px;">
          <span class="dropdown">
            <button class="btn btn-default dropdown-toggle" type="button" id="menu1" data-toggle="dropdown">
            Options: <span class="caret"></span></button>
            <ul class="dropdown-menu" role="menu" aria-labelledby="menu1">
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  href="emailReminder.htm">New Email Reminder</a></li>
            </ul>
          </span>

        </div>
    </div>


    <div id="paging"></div>
    <div id="listofpagesdiv">
        <table class="table">
            <thead>
                <tr>
                    <th>Subject</th>
                    <th>To</th>
                    <th>Status</th>
                    <th>Resend</th>
                    <th>timePeriod</th>
                    <th>rid</th>
                </tr>
            </thead>
            <tbody>
                <tr ng-repeat="rec in allReminders">
                    <td>{{rec.subject}}</td>
                    <td>
                        <b>{{rec.assignee}}</b>
                    </td>

                    <td>
                        {{rec.isOpen?"Open":"Closed"}}
                    </td>
                    <td>
                        <a href="sendemailReminder.htm?rid={{rec.id}}">Resend</a>
                    </td>
                    <td></td>
                    <td>{{rec.id}}</td>
                </tr>
            </tbody>
        </table>
    </div>


</div>
