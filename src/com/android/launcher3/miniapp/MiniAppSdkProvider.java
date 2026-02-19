/*
 * Copyright (C) 2026 The GrandiOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.miniapp;

import androidx.annotation.NonNull;

/**
 * Provides the miniapp-sdk.js script that is injected into every mini-app WebView.
 * This creates the window.miniapp API surface.
 *
 * The SDK wraps NativeBridge calls in a promise-based API so mini-app developers
 * get a clean async interface.
 */
public final class MiniAppSdkProvider {

    private MiniAppSdkProvider() {}

    /**
     * Returns the full miniapp-sdk.js script to be injected via evaluateJavascript.
     */
    @NonNull
    public static String getSdkScript() {
        return SDK_SCRIPT;
    }

    // The SDK is defined as a Java string constant. In a production build,
    // this could be loaded from an asset file instead.
    private static final String SDK_SCRIPT = "(function() {\n"
            + "  'use strict';\n"
            + "  if (window.miniapp) return;\n"
            + "\n"
            // --- Async callback infrastructure ---
            + "  var _callbackId = 0;\n"
            + "  window.__miniapp_callbacks = {};\n"
            + "\n"
            + "  function callAsync(bridgeMethod, args) {\n"
            + "    return new Promise(function(resolve, reject) {\n"
            + "      var id = ++_callbackId;\n"
            + "      window.__miniapp_callbacks[id] = {resolve:resolve, reject:reject};\n"
            + "      args.push(id);\n"
            + "      bridgeMethod.apply(NativeBridge, args);\n"
            + "    });\n"
            + "  }\n"
            + "\n"
            // --- Lifecycle callbacks ---
            + "  var _lifecycleFg=[], _lifecycleBg=[], _lifecycleTerm=[];\n"
            + "  window.__miniapp_lifecycle_foreground = function() {\n"
            + "    _lifecycleFg.forEach(function(cb){try{cb();}catch(e){}});\n"
            + "  };\n"
            + "  window.__miniapp_lifecycle_background = function() {\n"
            + "    _lifecycleBg.forEach(function(cb){try{cb();}catch(e){}});\n"
            + "  };\n"
            + "  window.__miniapp_lifecycle_terminate = function() {\n"
            + "    _lifecycleTerm.forEach(function(cb){try{cb();}catch(e){}});\n"
            + "  };\n"
            + "\n"
            // --- IPC handler (called by Java IPC router) ---
            + "  var _ipcHandler = null;\n"
            + "  window.__miniapp_ipc_handler = function(msg) {\n"
            + "    if (_ipcHandler) { try { _ipcHandler(msg); } catch(e) {} }\n"
            + "  };\n"
            + "\n"
            // --- Connectivity handler (called by Java connectivity monitor) ---
            + "  var _connectivityHandlers = [];\n"
            + "  window.__miniapp_connectivity_handler = function(online) {\n"
            + "    _connectivityHandlers.forEach(function(cb){"
            + "try{cb(online);}catch(e){}});\n"
            + "  };\n"
            + "\n"
            // --- Agent tool registry ---
            + "  window.__miniapp_agent_tools = {};\n"
            + "\n"
            // --- Main API ---
            + "  window.miniapp = {\n"
            + "    native: {\n"
            // Camera
            + "      camera: {\n"
            + "        capture: function(opts) {\n"
            + "          return callAsync(NativeBridge.requestCamera,\n"
            + "            [JSON.stringify(opts || {})]);\n"
            + "        },\n"
            + "        scan: function() {\n"
            + "          return callAsync(NativeBridge.requestCamera,\n"
            + "            [JSON.stringify({mode:'scan'})]);\n"
            + "        }\n"
            + "      },\n"
            // NFC
            + "      nfc: {\n"
            + "        read: function() {\n"
            + "          return callAsync(NativeBridge.readNFC, []);\n"
            + "        },\n"
            + "        write: function(data) {\n"
            + "          return callAsync(NativeBridge.writeNFC,\n"
            + "            [JSON.stringify(data || {})]);\n"
            + "        }\n"
            + "      },\n"
            // Biometric
            + "      biometric: {\n"
            + "        authenticate: function(reason) {\n"
            + "          return callAsync(NativeBridge.authenticateBiometric,\n"
            + "            [reason || 'Verify your identity']);\n"
            + "        }\n"
            + "      },\n"
            // Contacts
            + "      contacts: {\n"
            + "        search: function(query) {\n"
            + "          return callAsync(NativeBridge.readContacts,\n"
            + "            [query || '']);\n"
            + "        }\n"
            + "      },\n"
            // SMS
            + "      sms: {\n"
            + "        send: function(number, body) {\n"
            + "          return callAsync(NativeBridge.sendSMS,\n"
            + "            [number || '', body || '']);\n"
            + "        }\n"
            + "      },\n"
            // Notifications
            + "      notifications: {\n"
            + "        send: function(title, body, channel) {\n"
            + "          return callAsync(NativeBridge.sendNotification,\n"
            + "            [title || '', body || '', channel || 'default']);\n"
            + "        }\n"
            + "      },\n"
            // Sensors
            + "      sensors: {\n"
            + "        subscribe: function(type, callback) {\n"
            + "          window['__miniapp_sensor_' + type] = callback;\n"
            + "          return callAsync(NativeBridge.subscribeSensor, [type]);\n"
            + "        },\n"
            + "        unsubscribe: function(type) {\n"
            + "          delete window['__miniapp_sensor_' + type];\n"
            + "          NativeBridge.unsubscribeSensor(type);\n"
            + "        }\n"
            + "      },\n"
            // Telephony
            + "      telephony: {\n"
            + "        getCarrierInfo: function() {\n"
            + "          var raw = NativeBridge.getCarrierInfo(0);\n"
            + "          try { return JSON.parse(raw); } catch(e) { return raw; }\n"
            + "        }\n"
            + "      },\n"
            // Geolocation
            + "      location: {\n"
            + "        getCurrentPosition: function() {\n"
            + "          return callAsync(NativeBridge.getCurrentPosition, []);\n"
            + "        }\n"
            + "      },\n"
            // Clipboard
            + "      clipboard: {\n"
            + "        write: function(text) {\n"
            + "          NativeBridge.clipboardWrite(text || '');\n"
            + "        },\n"
            + "        read: function() {\n"
            + "          return callAsync(NativeBridge.clipboardRead, []);\n"
            + "        }\n"
            + "      },\n"
            // File picker
            + "      files: {\n"
            + "        pick: function(mimeType) {\n"
            + "          return callAsync(NativeBridge.pickFile,\n"
            + "            [mimeType || '*/*']);\n"
            + "        }\n"
            + "      }\n"
            + "    },\n"
            + "\n"
            // Storage
            + "    storage: {\n"
            + "      get: function(key) {\n"
            + "        var raw = NativeBridge.storageGet(key);\n"
            + "        if (raw === null || raw === undefined) return null;\n"
            + "        try { return JSON.parse(raw); } catch(e) { return raw; }\n"
            + "      },\n"
            + "      set: function(key, value) {\n"
            + "        NativeBridge.storageSet(key, JSON.stringify(value));\n"
            + "      },\n"
            + "      delete: function(key) {\n"
            + "        NativeBridge.storageDelete(key);\n"
            + "      }\n"
            + "    },\n"
            + "\n"
            // Lifecycle
            + "    lifecycle: {\n"
            + "      onForeground: function(cb) { _lifecycleFg.push(cb); },\n"
            + "      onBackground: function(cb) { _lifecycleBg.push(cb); },\n"
            + "      onTerminate: function(cb) { _lifecycleTerm.push(cb); }\n"
            + "    },\n"
            + "\n"
            // Network / connectivity
            + "    network: {\n"
            + "      onConnectivityChange: function(cb) {\n"
            + "        _connectivityHandlers.push(cb);\n"
            + "      },\n"
            + "      isOnline: function() {\n"
            + "        return navigator.onLine;\n"
            + "      }\n"
            + "    },\n"
            + "\n"
            // Agent
            + "    agent: {\n"
            + "      registerTool: function(name, handler) {\n"
            + "        window.__miniapp_agent_tools[name] = handler;\n"
            + "      },\n"
            + "      emitEvent: function(eventType, data) {\n"
            + "        NativeBridge.agentEvent(eventType, JSON.stringify(data || {}));\n"
            + "      }\n"
            + "    },\n"
            + "\n"
            // IPC
            + "    ipc: {\n"
            + "      send: function(targetApp, action, data) {\n"
            + "        var raw = NativeBridge.ipcSend(targetApp, action,\n"
            + "          JSON.stringify(data || {}));\n"
            + "        try { return JSON.parse(raw); } catch(e) { return raw; }\n"
            + "      },\n"
            + "      onMessage: function(cb) { _ipcHandler = cb; }\n"
            + "    },\n"
            + "\n"
            // Permissions
            + "    permissions: {\n"
            + "      request: function(permission) {\n"
            + "        return callAsync(NativeBridge.requestPermission, [permission]);\n"
            + "      },\n"
            + "      check: function(permission) {\n"
            + "        var raw = NativeBridge.checkPermission(permission);\n"
            + "        try { return JSON.parse(raw); } catch(e) { return raw; }\n"
            + "      }\n"
            + "    },\n"
            + "\n"
            // Runtime info
            + "    runtime: {\n"
            + "      version: '1.1.0',\n"
            + "      platform: 'grandios'\n"
            + "    }\n"
            + "  };\n"
            + "\n"
            // Freeze all API surfaces to prevent tampering
            + "  Object.freeze(window.miniapp.native.camera);\n"
            + "  Object.freeze(window.miniapp.native.nfc);\n"
            + "  Object.freeze(window.miniapp.native.biometric);\n"
            + "  Object.freeze(window.miniapp.native.contacts);\n"
            + "  Object.freeze(window.miniapp.native.sms);\n"
            + "  Object.freeze(window.miniapp.native.notifications);\n"
            + "  Object.freeze(window.miniapp.native.sensors);\n"
            + "  Object.freeze(window.miniapp.native.telephony);\n"
            + "  Object.freeze(window.miniapp.native.location);\n"
            + "  Object.freeze(window.miniapp.native.clipboard);\n"
            + "  Object.freeze(window.miniapp.native.files);\n"
            + "  Object.freeze(window.miniapp.native);\n"
            + "  Object.freeze(window.miniapp.storage);\n"
            + "  Object.freeze(window.miniapp.lifecycle);\n"
            + "  Object.freeze(window.miniapp.network);\n"
            + "  Object.freeze(window.miniapp.agent);\n"
            + "  Object.freeze(window.miniapp.ipc);\n"
            + "  Object.freeze(window.miniapp.permissions);\n"
            + "  Object.freeze(window.miniapp.runtime);\n"
            + "  Object.freeze(window.miniapp);\n"
            + "\n"
            + "  console.log('[MiniApp SDK] Initialized v1.1.0');\n"
            + "})();\n";
}
