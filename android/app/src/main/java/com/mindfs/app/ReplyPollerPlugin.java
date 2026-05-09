package com.mindfs.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "ReplyPoller",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class ReplyPollerPlugin extends Plugin {
    private static final String TAG = "MindFSReplyPoller";

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            resolvePermission(call, PermissionState.GRANTED);
            return;
        }
        PermissionState state = getPermissionState("notifications");
        if (state == PermissionState.GRANTED) {
            resolvePermission(call, state);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
    }

    @PermissionCallback
    private void notificationPermissionCallback(PluginCall call) {
        resolvePermission(call, getPermissionState("notifications"));
    }

    @PluginMethod
    public void configure(PluginCall call) {
        String apiBaseUrl = call.getString("apiBaseUrl", "").trim();
        if (apiBaseUrl.isEmpty()) {
            call.reject("apiBaseUrl is required");
            return;
        }
        Intent intent = new Intent(getContext(), ReplyPollerService.class);
        intent.setAction(ReplyPollerService.ACTION_CONFIGURE);
        intent.putExtra(ReplyPollerService.EXTRA_API_BASE_URL, apiBaseUrl);
        intent.putExtra(ReplyPollerService.EXTRA_TOKEN, call.getString("token", ""));
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        Intent intent = new Intent(getContext(), ReplyPollerService.class);
        intent.setAction(ReplyPollerService.ACTION_RESUME);
        startForeground(intent);
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        Intent intent = new Intent(getContext(), ReplyPollerService.class);
        intent.setAction(ReplyPollerService.ACTION_PAUSE);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void clearCompleted(PluginCall call) {
        Intent intent = new Intent(getContext(), ReplyPollerService.class);
        intent.setAction(ReplyPollerService.ACTION_CLEAR_COMPLETED);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), ReplyPollerService.class);
        intent.setAction(ReplyPollerService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve();
    }

    private void startForeground(Intent intent) {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    private void resolvePermission(PluginCall call, PermissionState state) {
        JSObject result = new JSObject();
        result.put("state", state == null ? "prompt" : state.toString());
        result.put("granted", state == PermissionState.GRANTED);
        call.resolve(result);
    }
}
