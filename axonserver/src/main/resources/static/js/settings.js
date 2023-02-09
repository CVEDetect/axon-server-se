/*
 *  Copyright (c) 2017-2023 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

//# sourceURL=settings.js
globals.pageView = new Vue({
                               el: '#settings',
                               data: {
                                   license: {
                                       featureList: []
                                   },
                                   status: {},
                                   node: {},
                                   timer: null,
                                   nodes: [],
                                   contexts: [],
                                   context: null,
                                   webSocketInfo: globals.webSocketInfo,
                                   subscription: null,
                                   initialized: globals.initialized,
                                   initializeMode: "init",
                                   initContext: "default",
                                   joinHost: null,
                                   joinPort: 8224
                               },
                               mounted() {

                                   axios.get("v1/public/license").then(response => {
                                       this.license = response.data
                                   });
                                   axios.get("v1/public/me").then(response => {
                                       this.node = response.data
                                   });
                                   axios.get("v1/public").then(response => {
                                       this.nodes = response.data
                                   });
                                   this.timer = setInterval(this.reloadStatus, 5000);
                                       let me = this;
                                   if (globals.isEnterprise()) {
                                       me.webSocketInfo.subscribe('/topic/cluster', function () {
                                           me.initOverview();
                                       }, function (sub) {
                                           me.subscription = sub;
                                       });
                                   }
                                       axios.get("v1/public/visiblecontexts?includeAdmin=false").then(response => {
                                           for (let i = 0; i < response.data.length; i++) {
                                               me.contexts.push(response.data[i]);
                                               if (!me.context && !response.data[i].startsWith("_")) {
                                                   me.context = response.data[i];
                                               }
                                           }
                                           me.reloadStatus()
                                       });
                               },
                               beforeDestroy() {
                                   clearInterval(this.timer);
                                   if (this.subscription) {
                                       this.subscription.unsubscribe();
                                   }
                               },
                               methods: {
                                   initOverview: function () {
                                       axios.get("v1/public").then(response => {
                                           this.nodes = response.data
                                       });
                                   },
                                   downloadTemplate: function () {
                                       axios.get("v1/cluster/download-template").then(response => {
                                           let blob = new Blob([response.data], {type: 'application/text'});
                                           let link = document.createElement('a');
                                           link.href = window.URL.createObjectURL(blob);
                                           link.download = 'cluster-template.yml';
                                           link.click();
                                       });
                                   },
                                   reloadStatus: function () {
                                       if (this.context) {
                                           axios.get("v1/public/status?context=" + this.context).then(response => {
                                               this.status = response.data
                                           });
                                       }
                                   },
                                   resetEvents() {
                                       if (confirm("Are you sure you want to delete all event and snapshot data?")) {
                                           axios.delete("v1/devmode/purge-events").then(response => {
                                               this.reloadStatus()
                                           });
                                       }
                                   },
                                   initCluster() {
                                       if (!this.initContext) {
                                           axios.post("v1/context/init").then(_ => {
                                               location.reload();
                                           })
                                       } else {
                                           axios.post("v1/context/init?context=" + this.initContext).then(_ => {
                                               location.reload();
                                           })
                                       }
                                   },
                                   joinCluster() {
                                       if (!this.joinHost) {
                                           alert("Enter the internal hostname of a member of the existing cluster");
                                           return
                                       }

                                       axios.post("v1/cluster", {
                                           "internalHostName": this.joinHost,
                                           "internalGrpcPort": this.joinPort
                                       }).then(_ => {
                                           location.reload();
                                       })
                                   }
                               }
                           });
