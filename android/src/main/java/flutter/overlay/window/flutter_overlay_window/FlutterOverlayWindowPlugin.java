package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterOverlayWindowPlugin implements
        FlutterPlugin, ActivityAware, BasicMessageChannel.MessageHandler, MethodCallHandler,
        PluginRegistry.ActivityResultListener {

    private MethodChannel channel;
    private Context context;
    private Activity mActivity;
    private ActivityPluginBinding activityBinding;
    private BasicMessageChannel<Object> messenger;
    private Result pendingResult;
    final int REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.CHANNEL_TAG);
        channel.setMethodCallHandler(this);

        messenger = new BasicMessageChannel<>(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.MESSENGER_TAG,
                JSONMessageCodec.INSTANCE);
        messenger.setMessageHandler(this);

        WindowSetup.messenger = messenger;
        WindowSetup.messenger.setMessageHandler(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("checkPermission")) {
            result.success(checkOverlayPermission());
        } else if (call.method.equals("requestPermission")) {
            requestPermission(result);
        } else if (call.method.equals("showOverlay")) {
            if (!checkOverlayPermission()) {
                result.error("PERMISSION", "overlay permission is not enabled", null);
                return;
            }
            Integer height = call.argument("height");
            Integer width = call.argument("width");
            String alignment = call.argument("alignment");
            String flag = call.argument("flag");
            String overlayTitle = call.argument("overlayTitle");
            String overlayContent = call.argument("overlayContent");
            String notificationVisibility = call.argument("notificationVisibility");
            Boolean enableDrag = call.argument("enableDrag");
            String positionGravity = call.argument("positionGravity");
            Map<String, Integer> startPosition = call.argument("startPosition");
            int startX = startPosition != null ? startPosition.getOrDefault("x", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            int startY = startPosition != null ? startPosition.getOrDefault("y", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;

            WindowSetup.width = width != null ? width : -1;
            WindowSetup.height = height != null ? height : -1;
            WindowSetup.enableDrag = enableDrag != null ? enableDrag : false;
            WindowSetup.setGravityFromAlignment(alignment != null ? alignment : "center");
            WindowSetup.setFlag(flag != null ? flag : "flagNotFocusable");
            WindowSetup.overlayTitle = overlayTitle;
            WindowSetup.overlayContent = overlayContent == null ? "" : overlayContent;
            WindowSetup.positionGravity = positionGravity;
            WindowSetup.setNotificationVisibility(notificationVisibility);

            final Intent intent = new Intent(context, OverlayService.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("startX", startX);
            intent.putExtra("startY", startY);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                result.success(null);
            } catch (Exception e) {
                result.error("SERVICE_START_FAILED", e.getMessage(), null);
            }
        } else if (call.method.equals("isOverlayActive")) {
            result.success(OverlayService.isRunning);
        } else if (call.method.equals("moveOverlay")) {
            Integer x = call.argument("x");
            Integer y = call.argument("y");
            if (x == null || y == null) {
                result.error("INVALID_ARGUMENTS", "x and y must not be null", null);
                return;
            }
            result.success(OverlayService.moveOverlay(x, y));
        } else if (call.method.equals("getOverlayPosition")) {
            result.success(OverlayService.getCurrentPosition());
        } else if (call.method.equals("closeOverlay")) {
            try {
                if (OverlayService.isRunning) {
                    final Intent i = new Intent(context, OverlayService.class);
                    context.stopService(i);
                }
                result.success(true);
            } catch (Exception e) {
                result.error("SERVICE_STOP_FAILED", e.getMessage(), null);
            }
        } else {
            result.notImplemented();
        }
    }

    private void requestPermission(@NonNull Result result) {
        if (pendingResult != null) {
            result.error("PERMISSION_IN_PROGRESS", "A permission request is already in progress", null);
            return;
        }
        if (checkOverlayPermission()) {
            result.success(true);
            return;
        }
        if (mActivity == null) {
            result.error("NO_ACTIVITY", "Overlay permission request requires a foreground Activity", null);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
            try {
                pendingResult = result;
                mActivity.startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_PERMISSION);
            } catch (ActivityNotFoundException | SecurityException e) {
                try {
                    Intent genericIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    mActivity.startActivityForResult(genericIntent, REQUEST_CODE_FOR_OVERLAY_PERMISSION);
                } catch (Exception ex) {
                    pendingResult = null;
                    result.error("PERMISSION_REQUEST_FAILED", ex.getMessage(), null);
                }
            } catch (Exception e) {
                pendingResult = null;
                result.error("PERMISSION_REQUEST_FAILED", e.getMessage(), null);
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        if (WindowSetup.messenger != null) {
            WindowSetup.messenger.setMessageHandler(null);
        }
        if (messenger != null) {
            messenger.setMessageHandler(null);
            messenger = null;
        }
        if (pendingResult != null) {
            Result resultToReply = pendingResult;
            pendingResult = null;
            resultToReply.error("ENGINE_DETACHED", "Engine detached before permission result", null);
        }
        context = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        mActivity = binding.getActivity();
        activityBinding = binding;
        binding.addActivityResultListener(this);
        if (FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG) == null) {
            FlutterEngineGroup enn = new FlutterEngineGroup(context);
            DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain");
            FlutterEngine engine = enn.createAndRunEngine(context, dEntry);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
            activityBinding = null;
        }
        this.mActivity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
            activityBinding = null;
        }
        this.mActivity = null;
        if (pendingResult != null) {
            Result resultToReply = pendingResult;
            pendingResult = null;
            resultToReply.error("ACTIVITY_DESTROYED", "Activity was detached before permission result", null);
        }
    }

    @Override
    public void onMessage(@Nullable Object message, @NonNull BasicMessageChannel.Reply reply) {
        FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (cachedEngine != null) {
            BasicMessageChannel overlayMessageChannel = new BasicMessageChannel(
                    cachedEngine.getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
            overlayMessageChannel.send(message, reply);
        } else {
            reply.reply(null);
        }
    }

    private boolean checkOverlayPermission() {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FOR_OVERLAY_PERMISSION) {
            Result resultToReply = pendingResult;
            pendingResult = null;
            if (resultToReply != null) {
                resultToReply.success(checkOverlayPermission());
            }
            return true;
        }
        return false;
    }

}
