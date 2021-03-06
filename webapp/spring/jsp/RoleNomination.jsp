<%@page errorPage="/spring/jsp/error.jsp"
%><%@ include file="include.jsp"
%><%

    ar.assertLoggedIn("Must be logged in to edit roles");

    String pageId      = ar.reqParam("pageId");
    String siteId      = ar.reqParam("siteId");
    String roleName    = ar.reqParam("role");
    String termKey    = ar.reqParam("term");
    
    //page must work for both workspaces and for sites
    NGContainer ngc = ar.getCogInstance().getWorkspaceOrSiteOrFail(siteId, pageId);
    ar.setPageAccessLevels(ngc);
    UserProfile uProf = ar.getUserProfile();

    CustomRole theRole = ngc.getRole(roleName);
    JSONObject role = theRole.getJSONDetail();


%>

<script type="text/javascript">

var app = angular.module('myApp', ['ui.bootstrap','ngTagsInput','ui.bootstrap.datetimepicker']);
app.controller('myCtrl', function($scope, $http, $modal, AllPeople) {
    $scope.role = <%role.write(out,2,4);%>;
    window.setMainPageTitle("Nominate Role: "+$scope.role.name);
    $scope.termKey = "<%ar.writeJS(termKey);%>";
    $scope.thisUser = "<%ar.writeJS(ar.getBestUserId());%>";
    $scope.comment = "";
    $scope.setComment = function(newComm) {
        $scope.comment = newComm;
    }
    if (!$scope.role.state) {
        $scope.role.state = "Nominating";
    }
    
    $scope.showInput = false;

    $scope.showInput = false;
    $scope.showError = false;
    $scope.errorMsg = "";
    $scope.errorTrace = "";
    $scope.showTrace = false;
    $scope.reportError = function(serverErr) {
        errorPanelHandler($scope, serverErr);
    };

    $scope.findTerm = function() {
        var res = {};
        $scope.role.terms.forEach( function(term) {
            if (term.key == $scope.termKey) {
                res = term;
            }
        });
        return res;
    }
    $scope.term = $scope.findTerm();
    $scope.stateStyle = function() {
        if ($scope.term.state=="Nominating") {
            return {"background-color": "lightgreen"};
        }
        if ($scope.term.state=="Changing") {
            return {"background-color": "skyblue"};
        }
        if ($scope.term.state=="Proposing") {
            return {"background-color": "yellow"};
        }
        if ($scope.term.state=="Completed") {
            return {"background-color": "darkgray"};
        }
    }
    $scope.isNominating = function() {
        return ($scope.term.state=="Nominating");
    }
    $scope.showNominations = function() {
        return ($scope.term.state!="Completed");
    }
    $scope.isProposingCompleting = function() {
        return ($scope.term.state=="Proposing" || $scope.term.state=="Completed");
    }
    $scope.isProposing = function() {
        return ($scope.term.state=="Proposing");
    }
    $scope.isCompleted = function() {
        return ($scope.term.state=="Completed");
    }
    
    $scope.loadPersonList = function(query) {
        return AllPeople.findMatchingPeople(query);
    }
    
    $scope.getDays = function(term) {
        if (term.termStart<100000) {
            return 0;
        }
        if (term.termEnd<100000) {
            return 0;
        }
        var diff = Math.floor((term.termEnd - term.termStart)/(1000*60*60*24));
        return diff;
    }
    $scope.getNominations = function() {
        if (!$scope.isNominating()) {
            return $scope.term.nominations;
        }
        var ret = [];
        $scope.term.nominations.forEach( function(item) {
            if (item.owner == $scope.thisUser) {
                ret.push(item);
            }
        });
        return ret;
    }
    $scope.missingNomination = function() {
        var ret = true;
        $scope.term.nominations.forEach( function(item) {
            if (item.owner == $scope.thisUser) {
                ret = false;
            }
        });
        return ret;
    }
   
    $scope.consent = function() {
        $scope.updateResponse("Consent");
    }
    $scope.object = function() {
        $scope.updateResponse("Object");
    }
    $scope.updateResponse = function(choice) {
        var respObj = {};
        respObj.owner = $scope.thisUser;
        respObj.comment = $scope.comment;
        respObj.choice = choice;
        var termObj = {};
        termObj.key = $scope.termKey;
        termObj.responses = [];
        termObj.responses.push(respObj);
        $scope.updateTerm(termObj);
    }
    $scope.updateState = function(newState) {
        var termObj = {};
        termObj.key = $scope.termKey;
        termObj.state = newState;
        $scope.updateTerm(termObj);
    }
    $scope.updateNomination = function(nom) {
        var termObj = {};
        termObj.key = $scope.termKey;
        termObj.nominations = [];
        termObj.nominations.push(nom);
        $scope.updateTerm(termObj);
    }
    $scope.updatePlayers = function(newList) {
        var termObj = {};
        termObj.key = $scope.termKey;
        termObj.players = [];
        termObj.players = newList;
        $scope.updateTerm(termObj);
    }
    $scope.updateTerm = function(termObj) {
        var roleObj = {};
        roleObj.name = $scope.role.name;
        roleObj.terms = [];
        roleObj.terms.push(termObj);
        $scope.updateRole(roleObj);
    }
    $scope.updateRole = function(role) {
        console.log("UPDATING role: ", role);
        var key = role.name;
        var postURL = "roleUpdate.json?op=Update";
        var postdata = angular.toJson(role);
        $scope.showError=false;
        $http.post(postURL ,postdata)
        .success( function(data) {
            $scope.role = data;
            $scope.term = $scope.findTerm();
        })
        .error( function(data, status, headers, config) {
            $scope.reportError(data);
        });
    };
    
    
    
    $scope.openNominationModal = function (nom) {
        if (!$scope.showNominations()) {
            return;  //avoid nominiations when completed
        }
        var isNew = false;
        if (!nom) {   
            nom = {"owner":$scope.thisUser};
            isNew = true;
        }
        var modalInstance = $modal.open({
            animation: false,
            templateUrl: '<%=ar.retPath%>templates/NominationModal.html?t=<%=System.currentTimeMillis()%>',
            controller: 'NominationModal',
            size: 'lg',
            backdrop: "static",
            resolve: {
                nomination: function () {
                    return JSON.parse(JSON.stringify(nom));
                },
                isNew: function() {return isNew;},
                parentScope: function() { return $scope; }
            }
        });

        modalInstance.result.then(function (message) {
            //what to do when closing the role modal?
        }, function () {
            //cancel action - nothing really to do
        });
    };

});

</script>
<script src="../../../jscript/AllPeople.js"></script>

<div ng-app="myApp" ng-controller="myCtrl">

<%@include file="ErrorPanel.jsp"%>

    <div class="col-12">
        <div class="rightDivContent" style="margin-right:100px;">
          <span class="dropdown">
            <button class="btn btn-default btn-raised dropdown-toggle" 
                    type="button" id="menu1" data-toggle="dropdown" ng-style="stateStyle()">
                {{term.state}}: <span class="caret"></span></button>
            <ul class="dropdown-menu" role="menu" aria-labelledby="menu1">
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  ng-click="updateState('Nominating')">State &#10132; Nominating</a></li>
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  ng-click="updateState('Changing')">State &#10132; Changing</a></li>
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  ng-click="updateState('Proposing')">State &#10132; Proposing</a></li>
              <li role="presentation"><a role="menuitem" tabindex="-1"
                  ng-click="updateState('Completed')">State &#10132; Completed</a></li>
            </ul>
          </span>
        </div>
        <div style="clear:both"></div>
    </div>


    <div class="row">
        <div class="col-md-6 col-sm-12">
            <div class="form-group">
                <label for="status">Term Start:</label>
                <span class="dropdown-toggle form-control" id="dropdown2" role="button" 
                    data-toggle="dropdown" data-target="#">
                    {{ term.termStart | date:'dd-MMM-yyyy \'at\' HH:mm  \' &nbsp;  GMT\'Z' }}
                </span>
                <ul class="dropdown-menu" role="menu" aria-labelledby="dLabel">
                    <datetimepicker
                        data-ng-model="term.termStart"
                        data-datetimepicker-config="{ dropdownSelector: '#dropdown2',minuteStep: 15,minView:'hour'}"
                        data-on-set-time="onTimeSet(newDate, 'termStart')"/>
                </ul>                
            </div>
        </div>
        
        <div class="col-md-6 col-sm-12">
            <div class="form-group">
                <label for="status">Term End:</label>
                <span class="dropdown-toggle form-control" id="dropdown3" role="button" 
                    data-toggle="dropdown" data-target="#">
                    {{ term.termEnd | date:'dd-MMM-yyyy \'at\' HH:mm  \' &nbsp;  GMT\'Z' }}
                </span>
                <ul class="dropdown-menu" role="menu" aria-labelledby="dLabel">
                    <datetimepicker
                        data-ng-model="term.termEnd"
                        data-datetimepicker-config="{ dropdownSelector: '#dropdown3',minuteStep: 15,minView:'hour'}"
                        data-on-set-time="onTimeSet(newDate, 'termEnd')"/>
                </ul>                
            </div>
        </div>
    </div>
    <div>
        <div class="col-12">
            <div class="form-group">
                <label for="status">Duration:</label>
                <span >
                    {{ getDays(term) }} Days
                </span>
            </div>
        
            <div class="form-group" ng-show="showNominations()">
                <label for="synopsis">Nominations:</label>
                <table class="table">
                <tr>
                    <td></td>
                    <td></td>
                    <td><label>Nominee</label></td>
                    <td><label>Reason</label></td>
                </tr>
                <tr ng-repeat="nom in getNominations()" >
                    <td class="actions">
                        <button type="button" name="edit" class="btn btn-primary" 
                                ng-click="openNominationModal(nom)">
                            <span class="fa fa-edit"></span>
                        </button>
                    </td>
                    <td ng-click="openNominationModal(nom)">{{nom.owner}}</td>
                    <td ng-click="openNominationModal(nom)">{{nom.nominee}}</td>
                    <td ng-click="openNominationModal(nom)">{{nom.comment}}</td>
                </tr>
                <tr ng-show="missingNomination()">
                    <td class="actions">
                        <button type="button" name="edit" class="btn btn-primary" 
                                ng-click="openNominationModal()">
                            <span class="fa fa-edit"></span>
                        </button>
                    </td>
                    <td ng-click="openNominationModal()">{{thisUser}}</td>
                    <td ng-click="openNominationModal()" colspan="2"> 
                        <i>Click here to make a nomination</i></td>
                </tr>
                </table>
                <div ng-show="term.nominations.length==0" class="guideVocal">
                There are no nominations for this term at this time.
                </div>
            </div>
            <div ng-show="isNominating()" class="guideVocal">
                During the nomination phase, you can only see your own nomination!
            </div>
            <hr/>
        </div>
    </div>
    <div class="row" ng-show="isProposingCompleting()">
        <div class="col-md-6 col-sm-12">
            <div class="form-group">
                <label for="status">Players <span ng-hide="isCompleted()">(Proposed)</span></label>
                <tags-input ng-model="term.players" placeholder="Enter user name or id" 
                            display-property="name" key-property="uid" 
                            on-tag-added="updatePlayers(term.players)" 
                            on-tag-removed="updatePlayers(term.players)">
                    <auto-complete source="loadPersonList($query)"></auto-complete>
                </tags-input>
                <ul class="dropdown-menu" role="menu" aria-labelledby="menu2">
                   <li role="presentation"><a role="menuitem" title="{{add}}"
                      ng-click="">Remove Label:<br/>{{role.name}}</a></li>
                </ul>
            </div>
            <div class="form-group">
                <label for="status">Responses:</label>
                <table class="table">
                    <tr>
                        <td><label>Member</label></td>
                        <td><label>Choice</label></td>
                        <td><label>Comment</label></td>
                    </tr>
                    <tr ng-repeat="resp in term.responses" ng-click="setComment(resp.comment)">
                        <td>{{resp.owner}}</td>
                        <td>{{resp.choice}}</td>
                        <td>{{resp.comment}}</td>
                    </tr>
                </table>
            </div>
        </div>
        <div class="col-md-6 col-sm-12">
            <div class="form-group" ng-show="isProposing()">
                <label for="status">Your Response:</label>
                <textarea ng-model="comment" class="form-control"></textarea>
            </div>
            <div class="form-group" ng-show="isProposing()"">
                <button ng-click="consent()" class="btn btn-primary btn-raised">Consent</button>
                <button ng-click="object()" class="btn btn-primary btn-raised">Object</button>
            </div>
        </div>
    </div>

</div>

<script src="<%=ar.retPath%>templates/NominationModal.js"></script>
