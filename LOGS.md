19:25:49.958 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:49.958 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:50.536 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:50.537 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:50.537 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:25:50.570 Navigated to https://fadcam.fadseclab.com/stream/?device=bf892fd6aeb8b17c
19:25:50.583 Reloaded
19:25:50.589 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:25:50.590 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:50.590 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:50.590 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:50.590 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:50.590 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:25:50.787 [RealtimeCommand] 📡 Channel status: CHANNEL_ERROR RealtimeCommandService.js:68:25
19:25:50.787 [RealtimeCommand] ❌ Channel error RealtimeCommandService.js:75:29
19:25:51.000 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:51.000 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:51.004 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/2 404  152ms]

19:25:51.158 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:51.158 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:51.158 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:51.158 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:25:51.350 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://static.cloudflareinsights.com/beacon.min.js/vcd15cbe7772f49c399c6a5babf22c1241717689176015. (Reason: CORS request did not succeed). Status code: (null).
19:25:51.351 None of the “sha512” hashes in the integrity attribute match the content of the subresource at “https://static.cloudflareinsights.com/beacon.min.js/vcd15cbe7772f49c399c6a5babf22c1241717689176015”. The computed hash is “z4PhNX7vuL3xVChQ1m2AB9Yg5AULVxXcg/SpIdNs6c5H0NE8XYXysP+DGNKHfuwvY7kxvUdBeoGlODJ6+SfaPg==”. stream
19:25:51.643 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://static.cloudflareinsights.com/beacon.min.js/vcd15cbe7772f49c399c6a5babf22c1241717689176015. (Reason: CORS request did not succeed). Status code: (null).
19:25:51.643 None of the “sha512” hashes in the integrity attribute match the content of the subresource at “https://static.cloudflareinsights.com/beacon.min.js/vcd15cbe7772f49c399c6a5babf22c1241717689176015”. The computed hash is “z4PhNX7vuL3xVChQ1m2AB9Yg5AULVxXcg/SpIdNs6c5H0NE8XYXysP+DGNKHfuwvY7kxvUdBeoGlODJ6+SfaPg==”. stream
19:25:51.646 [Volume] Buttons found: YES stream:3608:25
19:25:51.646 [DashboardViewModel] Initializing... DashboardViewModel.js:21:17
19:25:51.646 📱 [/status] Dashboard sending request to https://fadcam.fadseclab.com/status ApiService.js:152:21
19:25:51.648 ✅ Avatar component initialized in hero card stream:3776:29
19:25:51.648 🧹 [CACHE-CLEANUP] Starting cleanup of 0 cached notifications FadexNotificationManager.js:656:17
19:25:51.648 🧹 [CACHE-CLEANUP] Complete: removed=0, remaining=0 FadexNotificationManager.js:689:17
19:25:51.648 [Bell] bellBtn found: 
<button id="fadex-notification-bell" class="fadex-bell-btn" title="Fadex Notifications">
stream:3857:25
19:25:51.648 [Bell] Adding click listener to bell stream:3859:29
19:25:51.648 ✅ Fadex Notification Manager initialized - Mode: PRODUCTION (GitHub only) stream:3924:25
19:25:51.648 📥 [CACHE-LOAD] No cached notifications found FadexNotificationManager.js:622:21
19:25:51.648 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:51.648 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:51.649 XHRGET
https://fadcam.fadseclab.com/status
[HTTP/2 404  139ms]

19:25:51.649 [Profile] DOM loaded, setting up dropdown stream:4167:21
19:25:51.649 [Profile] profileBtn: 
<button id="profileBtn" class="profile-btn" title="Profile & Security">
stream:4171:21
19:25:51.649 [Profile] profileDropdown: 
<div id="profileDropdown" class="profile-dropdown">
stream:4172:21
19:25:51.649 [Profile] Elements found, adding click listener stream:4175:25
19:25:51.649 [FadCamRemote] Web access detected, adding cloud features... fadcam-remote.js:365:13
19:25:51.649 [FadCamRemote] Streaming mode for device: bf892fd6aeb8b17c fadcam-remote.js:370:15
19:25:51.649 [FadCamRemote] Initializing stream for device: bf892fd6aeb8b17c fadcam-remote.js:159:13
19:25:51.649 [FadCamRemote] getHandoffToken: 
Object { search: "?device=bf892fd6aeb8b17c", hasToken: false, tokenLength: undefined }
fadcam-remote.js:72:13
19:25:51.649 [FadCamRemote] Token check result: 
Object { handoffToken: null, willExchange: false }
fadcam-remote.js:167:15
19:25:51.649 [FadCamRemote] NO handoff token, checking stored session... fadcam-remote.js:194:17
19:25:51.649 [FadCamRemote] Stored session: exists Stream token: exists fadcam-remote.js:198:17
19:25:51.649 [ApiService] Stream context set: 
Object { deviceId: "bf892fd6aeb8b17c", deviceName: "Your Device", userId: "7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe", streamToken: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI3MDM1YjRhOC1mZDE0LTRjMGEtYThhZS1lNGVkMzRmMTExZmUiLCJkZXZpY2VfaWQiOiJiZjg5MmZkNmFlYjhiMTdjIiwiZW1haWwiOiJmYWRlZEBmYWRzZWNsYWIuY29tIiwidHlwZSI6InN0cmVhbV9hY2Nlc3MiLCJpYXQiOjE3Njk4NjQwMDAsImV4cCI6MTc2OTk1MDQwMH0.EWMJm-G-7lmmJ-lFHTRs30fSdWZa2oP2cTCrgyplikQ" }
ApiService.js:59:17
19:25:51.649 [ApiService] ⚡ Initializing Supabase Realtime for instant commands... ApiService.js:65:25
19:25:51.650 [RealtimeCommand] 📡 Connecting to channel: device:bf892fd6aeb8b17c RealtimeCommandService.js:58:21
19:25:51.650 [ApiService] Initialized cloud mode with stream context ApiService.js:538:13
19:25:51.650 [FadCamRemote] CloudApiService initialized fadcam-remote.js:227:17
19:25:51.650 [FadCamRemote] Stream context ready: 
Object { deviceId: "bf892fd6aeb8b17c", deviceName: "Your Device", userId: "7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe", streamToken: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI3MDM1YjRhOC1mZDE0LTRjMGEtYThhZS1lNGVkMzRmMTExZmUiLCJkZXZpY2VfaWQiOiJiZjg5MmZkNmFlYjhiMTdjIiwiZW1haWwiOiJmYWRlZEBmYWRzZWNsYWIuY29tIiwidHlwZSI6InN0cmVhbV9hY2Nlc3MiLCJpYXQiOjE3Njk4NjQwMDAsImV4cCI6MTc2OTk1MDQwMH0.EWMJm-G-7lmmJ-lFHTRs30fSdWZa2oP2cTCrgyplikQ" }
fadcam-remote.js:230:15
19:25:51.650 [DashboardViewModel] ☁️ Cloud mode ready, reinitializing... 
Object { deviceId: "bf892fd6aeb8b17c", deviceName: "Your Device", userId: "7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe", streamToken: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI3MDM1YjRhOC1mZDE0LTRjMGEtYThhZS1lNGVkMzRmMTExZmUiLCJkZXZpY2VfaWQiOiJiZjg5MmZkNmFlYjhiMTdjIiwiZW1haWwiOiJmYWRlZEBmYWRzZWNsYWIuY29tIiwidHlwZSI6InN0cmVhbV9hY2Nlc3MiLCJpYXQiOjE3Njk4NjQwMDAsImV4cCI6MTc2OTk1MDQwMH0.EWMJm-G-7lmmJ-lFHTRs30fSdWZa2oP2cTCrgyplikQ" }
DashboardViewModel.js:26:25
19:25:51.650 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:51.650 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:51.651 [ApiService] ✅ Realtime ready for instant command delivery ApiService.js:68:33
19:25:51.651 CSS Load Test - LED background: rgb(110, 118, 129) stream:26:21
19:25:51.651 ✅ CSS loaded successfully stream:30:25
19:25:51.655 XHRGET
https://fadcam.fadseclab.com/auth/check
[HTTP/2 404  147ms]

19:25:51.655 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/2 404  147ms]

19:25:51.792 📡 [/status] Response received in 146.00ms, status: 404 ApiService.js:160:21
19:25:51.792 ❌ [/status] Failed to fetch local status: Error: HTTP 404: 
    _getLocalStatus https://fadcam.fadseclab.com/stream/js/services/ApiService.js?v=2.2.0:163
    getStatus https://fadcam.fadseclab.com/stream/js/services/ApiService.js?v=2.2.0:143
    updateStatus https://fadcam.fadseclab.com/stream/js/viewmodels/DashboardViewModel.js:44
    initialize https://fadcam.fadseclab.com/stream/js/viewmodels/DashboardViewModel.js:32
    <anonymous> https://fadcam.fadseclab.com/stream/?device=bf892fd6aeb8b17c:3756
    EventListener.handleEvent* https://fadcam.fadseclab.com/stream/?device=bf892fd6aeb8b17c:3672
ApiService.js:174:21
19:25:51.793 ❌ [DashboardViewModel] Failed to update status: HTTP 404: DashboardViewModel.js:53:21
19:25:51.793 [Toast] First status load - not showing change notifications stream:2313:25
19:25:51.793 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:51.793 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:51.793 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:51.793 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:51.794 [DashboardViewModel] Started polling every 2 seconds DashboardViewModel.js:83:17
19:25:51.805 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:51.806 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:51.806 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:51.806 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:25:51.806 [AuthService] Auth check failed: 404 AuthService.js:82:25
19:25:51.806 [Auth] Initial check: 
Object { authEnabled: false, authenticated: false }
stream:3943:25
19:25:52.222 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:52.223 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:52.223 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:25:52.343 Cookie “__cf_bm” has been rejected for invalid domain. websocket
19:25:52.526 [RealtimeCommand] 📡 Channel status: SUBSCRIBED RealtimeCommandService.js:68:25
19:25:52.527 [RealtimeCommand] ✅ Connected to Supabase Realtime RealtimeCommandService.js:72:29
19:25:52.790 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/stream/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c/live.m3u8. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:52.790 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:25:52.790 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:52.791 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:52.791 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:52.791 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:52.791 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:25:53.796 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:53.796 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:53.808 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:53.808 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:53.822 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/2 404  136ms]

19:25:53.961 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:53.961 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:53.961 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:53.961 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:25:54.407 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:54.407 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:25:54.974 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/stream/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c/live.m3u8. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:54.976 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:25:54.976 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:54.976 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:54.976 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:54.976 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:54.976 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:25:55.801 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:55.801 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:55.810 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:55.810 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:55.814 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/3 404  200ms]

19:25:56.016 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:56.016 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:56.016 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:56.016 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:25:56.380 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:56.380 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:25:56.380 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:25:56.946 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/stream/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c/live.m3u8. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:56.947 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:25:56.947 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:56.947 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:56.947 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:56.947 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:56.948 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:25:57.801 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:57.801 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:57.810 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:57.810 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:57.825 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/3 404  386ms]

19:25:58.199 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:58.199 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:58.199 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:58.199 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:25:58.399 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:58.399 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:25:58.986 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/stream/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c/live.m3u8. (Reason: CORS request did not succeed). Status code: (null). 2
19:25:58.987 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:25:58.987 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:25:58.987 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:25:58.987 [Storage] No storage data: used=0, total=0 stream:1277:25
19:25:58.987 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:25:58.987 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:25:59.807 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:25:59.807 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:25:59.811 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:25:59.811 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:25:59.817 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/3 404  173ms]

19:25:59.972 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:25:59.972 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:25:59.972 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:25:59.972 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25
19:26:00.386 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:26:00.387 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c. (Reason: CORS request did not succeed). Status code: (null).
19:26:00.387 ☁️ [/status] Relay fetch failed: NetworkError when attempting to fetch resource. ApiService.js:221:25
19:26:00.946 Cross-Origin Request Blocked: The Same Origin Policy disallows reading the remote resource at https://live.fadseclab.com:8443/stream/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c/live.m3u8. (Reason: CORS request did not succeed). Status code: (null). 2
19:26:00.946 ✅ [/status] ☁️ Cloud (fallback): state=waiting, message=Waiting for device to start streaming... ApiService.js:234:21
19:26:00.947 [Alarm] Server state: 
Object { is_ringing: false, sound: "office_phone.mp3", duration_ms: -1, remaining_ms: 0 }
stream:2351:25
19:26:00.948 [Battery] Invalid battery data: percent=-1, status={"percent":-1,"status":"unknown","consumed":"N/A","remainingHours":-1,"warning":false,"warning_threshold":20} stream:1222:25
19:26:00.949 [Storage] No storage data: used=0, total=0 stream:1277:25
19:26:00.949 [Memory] No memory data: percent=0, total=0 stream:1312:25
19:26:00.949 ✅ [DashboardViewModel] ☁️ Status updated: waiting, streaming: false, clients: 0 DashboardViewModel.js:51:21
19:26:01.811 ☁️ [/status] Checking cloud status for device: bf892fd6aeb8b17c ApiService.js:185:21
19:26:01.811 ☁️ [/status] Fetching from relay: https://live.fadseclab.com:8443/api/status/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf892fd6aeb8b17c ApiService.js:198:21
19:26:01.812 🔄 [CHECK-UPDATES] Starting notification update check... FadexNotificationManager.js:75:17
19:26:01.812 🌐 [FETCH] Getting notifications from backend endpoint... FadexNotificationManager.js:322:21
19:26:01.836 XHRGET
https://fadcam.fadseclab.com/api/github/notification
[HTTP/3 404  175ms]

19:26:02.004 ❌ [FETCH] HTTP 404 FadexNotificationManager.js:329:25
19:26:02.004 ❌ [ERROR] HTTP 404 FadexNotificationManager.js:347:21
19:26:02.004 🔄 [CHECK-UPDATES] Received raw data: null FadexNotificationManager.js:79:21
19:26:02.004 ⚠️ [CHECK-UPDATES] No raw data or notifications property found FadexNotificationManager.js:82:25


    GLRecordingPipeline  I  [AUDIO_WRITE] Sample #1900, PTS=41.764s (41764454us), size=505b
 FragmentedMp4MuxerWrap  I  🎬 [FRAGMENT] #40: 294 KB, duration: 1407 ms
    CloudStreamUploader  W  Init segment not uploaded yet, skipping segment 40
                         I  📤 Sending HTTP PUT to: https://live.fadseclab.com:8443/upload/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf89...
    GLRecordingPipeline  W  ⚠️ SILENCE DETECTED: 10 consecutive 512-byte frames at sample #1921, 42.212454s
                         I  [AUDIO_WRITE] Sample #1950, PTS=42.831s (42831120us), size=512b
                         W  ⚠️ SILENCE DETECTED: 10 consecutive 512-byte frames at sample #1951, 42.852454s
    GLRecordingPipeline  I  [VIDEO_WRITE] Sample #630, PTS=42.265s (42265289us), size=12145b, keyframe=false
 FragmentedMp4MuxerWrap  W  [VLC-VIDEO-CONVERT] Video keyframe flag converted to Media3 C.BUFFER_FLAG_KEY_FRAME
    RemoteStreamManager  D  📊 [getStatusJson] Cache miss, generating fresh JSON...
                         D  📊 [getStatusJson] JSON generated successfully in 13ms, size: 1318 bytes
                         D  📋 [getStatusJson] JSON preview:
                         D  {"streaming": true, "mode": "stream_only", "state": "ready", "message": "Stream is ready for playback.", "is_recording": true, "fragments_buffered": 15, "buffer_size_mb": 3.27, "latest_sequence": 40, "oldest_sequence": 26, "active_connections": 0, "has_init_segment": true, "uptime_seconds": 46, "battery_details": {"percent": 100, "status": "Chargin...[truncated]
    CloudStreamUploader  E  ❌ Upload network failure after 569ms: SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
 FragmentedMp4MuxerWrap  I  🎬 [FRAGMENT] #41: 154 KB, duration: 1024 ms
    CloudStreamUploader  W  Init segment not uploaded yet, skipping segment 41
                         I  📤 Sending HTTP PUT to: https://live.fadseclab.com:8443/upload/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf89...
     CloudStatusManager  W  Status push error: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
 FragmentedMp4MuxerWrap  W  [VLC-VIDEO-CONVERT] Video keyframe flag converted to Media3 C.BUFFER_FLAG_KEY_FRAME
    CloudStreamUploader  E  ❌ Upload network failure after 572ms: SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
  BufferPoolAccessor2.0  D  bufferpool2 0xb400007bd4074028 : 4(1048576 size) total buffers - 4(1048576 size) used buffers - 0/5 (recycle/alloc) - 62/125 (fetch/transfer)
    GLRecordingPipeline  I  [AUDIO_WRITE] Sample #2000, PTS=43.898s (43897787us), size=507b
                         W  ⚠️ SILENCE DETECTED: 10 consecutive 512-byte frames at sample #2013, 44.17512s
 FragmentedMp4MuxerWrap  I  🎬 [FRAGMENT] #42: 283 KB, duration: 1394 ms
    CloudStreamUploader  W  Init segment not uploaded yet, skipping segment 42
                         I  📤 Sending HTTP PUT to: https://live.fadseclab.com:8443/upload/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf89...
    GLRecordingPipeline  I  [VIDEO_WRITE] Sample #660, PTS=44.242s (44241749us), size=11801b, keyframe=false
 FragmentedMp4MuxerWrap  W  [VLC-VIDEO-CONVERT] Video keyframe flag converted to Media3 C.BUFFER_FLAG_KEY_FRAME
    RemoteStreamManager  D  📊 [getStatusJson] Cache miss, generating fresh JSON...
                         D  📊 [getStatusJson] JSON generated successfully in 10ms, size: 1318 bytes
                         D  📋 [getStatusJson] JSON preview:
                         D  {"streaming": true, "mode": "stream_only", "state": "ready", "message": "Stream is ready for playback.", "is_recording": true, "fragments_buffered": 15, "buffer_size_mb": 3.28, "latest_sequence": 42, "oldest_sequence": 28, "active_connections": 0, "has_init_segment": true, "uptime_seconds": 48, "battery_details": {"percent": 100, "status": "Chargin...[truncated]
    CloudStreamUploader  E  ❌ Upload network failure after 578ms: SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
    GLRecordingPipeline  I  [AUDIO_WRITE] Sample #2050, PTS=44.964s (44964454us), size=505b
 FragmentedMp4MuxerWrap  I  🎬 [FRAGMENT] #43: 148 KB, duration: 1024 ms
    CloudStreamUploader  W  Init segment not uploaded yet, skipping segment 43
                         I  📤 Sending HTTP PUT to: https://live.fadseclab.com:8443/upload/7035b4a8-fd14-4c0a-a8ae-e4ed34f111fe/bf89...
     CloudStatusManager  W  Status push error: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
 FragmentedMp4MuxerWrap  W  [VLC-VIDEO-CONVERT] Video keyframe flag converted to Media3 C.BUFFER_FLAG_KEY_FRAME
