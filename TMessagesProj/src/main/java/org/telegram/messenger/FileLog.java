/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.telegram.messenger.time.FastDateFormat;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedFileDrawable;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public class FileLog {
    private OutputStreamWriter streamWriter = null;
    private FastDateFormat dateFormat = null;
    private FastDateFormat fileDateFormat = null;
    private DispatchQueue logQueue = null;

    private File currentFile = null;
    private File networkFile = null;
    private File tonlibFile = null;
    private boolean initied;
    public static boolean databaseIsMalformed = false;

    private OutputStreamWriter tlStreamWriter = null;
    private File tlRequestsFile = null;

    private final static String tag = "tmessages";
    private final static String mtproto_tag = "MTProto";

    private static volatile FileLog Instance = null;

    public static FileLog getInstance() {
        FileLog localInstance = Instance;
        if (localInstance == null) {
            synchronized (FileLog.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new FileLog();
                }
            }
        }
        return localInstance;
    }

    public FileLog() {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        init();
    }


    private static Gson gson;
    private static ExclusionStrategy exclusionStrategy;
    private static HashSet<String> excludeRequests;

    public static void dumpResponseAndRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error, long requestMsgId, long startRequestTimeInMillis, int requestToken) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION || !BuildVars.LOGS_ENABLED || request == null) {
            return;
        }
        String requestSimpleName = request.getClass().getSimpleName();
        checkGson();

        if (excludeRequests.contains(requestSimpleName) && error == null) {
            return;
        }
        try {
            String req = "req -> " + requestSimpleName + " : " + gson.toJson(request);
            String res = "null";
            if (response != null) {
                res = "res -> " + response.getClass().getSimpleName() + " : " + gson.toJson(response);
            } else if (error != null) {
                res = "err -> " + error.getClass().getSimpleName() + " : " + gson.toJson(error);
            }
            String finalRes = res;
            long time = System.currentTimeMillis();
            FileLog.getInstance().logQueue.postRunnable(() -> {
                try {
                    String metadata = "requestMsgId=" + requestMsgId + " requestingTime=" + (System.currentTimeMillis() - startRequestTimeInMillis) +  " request_token=" + requestToken + " account=" + account;
                    FileLog.getInstance().tlStreamWriter.write(getInstance().dateFormat.format(time) + " " + metadata);
                    FileLog.getInstance().tlStreamWriter.write("\n");
                    FileLog.getInstance().tlStreamWriter.write(req);
                    FileLog.getInstance().tlStreamWriter.write("\n");
                    FileLog.getInstance().tlStreamWriter.write(finalRes);
                    FileLog.getInstance().tlStreamWriter.write("\n\n");
                    FileLog.getInstance().tlStreamWriter.flush();

                    if (error != null) {
                        Log.e(mtproto_tag, metadata);
                        Log.e(mtproto_tag, req);
                        Log.e(mtproto_tag, finalRes);
                        Log.e(mtproto_tag, " ");
                    } else {
                        Log.d(mtproto_tag, metadata);
                        Log.d(mtproto_tag, req);
                        Log.d(mtproto_tag, finalRes);
                        Log.d(mtproto_tag, " ");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Throwable e) {
            FileLog.e(e, BuildVars.DEBUG_PRIVATE_VERSION);
        }
    }

    public static void dumpUnparsedMessage(TLObject message, long messageId, int account) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION || !BuildVars.LOGS_ENABLED || message == null) {
            return;
        }
        try {
            checkGson();
            getInstance().dateFormat.format(System.currentTimeMillis());
            String messageStr = "receive message -> " + message.getClass().getSimpleName() + " : " + (gsonDisabled ? message : gson.toJson(message));
            String res = "null";
            long time = System.currentTimeMillis();
            FileLog.getInstance().logQueue.postRunnable(() -> {
                try {
                    String metadata = getInstance().dateFormat.format(time) + " msgId=" + messageId + " account=" + account;

                    FileLog.getInstance().tlStreamWriter.write(metadata);
                    FileLog.getInstance().tlStreamWriter.write("\n");
                    FileLog.getInstance().tlStreamWriter.write(messageStr);
                    FileLog.getInstance().tlStreamWriter.write("\n\n");
                    FileLog.getInstance().tlStreamWriter.flush();

                    Log.d(mtproto_tag, "msgId=" + messageId + " account=" + account);
                    Log.d(mtproto_tag, messageStr);
                    Log.d(mtproto_tag, " ");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Throwable e) {
        }
    }

    private static boolean gsonDisabled;
    public static void disableGson(boolean disable) {
        gsonDisabled = disable;
    }

    private static HashSet<String> privateFields;
    private static void checkGson() {
        if (gson == null) {
            privateFields = new HashSet<>();
            privateFields.add("message");
            privateFields.add("phone");
            privateFields.add("about");
            privateFields.add("status_text");
            privateFields.add("bytes");
            privateFields.add("secret");
            privateFields.add("stripped_thumb");
            privateFields.add("strippedBitmap");

            privateFields.add("networkType");
            privateFields.add("disableFree");
            privateFields.add("mContext");
            privateFields.add("priority");
            privateFields.add("constructor");

            //exclude file loading
            excludeRequests = new HashSet<>();
            excludeRequests.add("TL_upload_getFile");
            excludeRequests.add("TL_upload_getWebFile");

            exclusionStrategy = new ExclusionStrategy() {

                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    if (privateFields.contains(f.getName()) || "message".equalsIgnoreCase(f.getName()) && String.class.equals(f.getDeclaredType())) {
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return clazz.isInstance(DispatchQueue.class) || clazz.isInstance(AnimatedFileDrawable.class) || clazz.isInstance(ColorStateList.class) || clazz.isInstance(Context.class);
                }
            };
            gson = new GsonBuilder()
                .addSerializationExclusionStrategy(exclusionStrategy)
                .registerTypeAdapter(byte[].class, new ByteArrayHexAdapter())
                .registerTypeAdapterFactory(RuntimeClassNameTypeAdapterFactory.of(TLObject.class, "type_", exclusionStrategy))
                .registerTypeHierarchyAdapter(TLObject.class, new TLObjectDeserializer())
                .create();
        }
    }

    public static class ByteArrayHexAdapter extends TypeAdapter<byte[]> {

        @Override
        public void write(JsonWriter out, byte[] value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            StringBuilder hex = new StringBuilder(2 + value.length * 2);
            hex.append("0x");
            for (byte b : value) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            out.value(hex.toString());
        }

        @Override
        public byte[] read(JsonReader in) throws IOException {
            String hex = in.nextString();
            int len = hex.length();
            byte[] result = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
            }
            return result;
        }
    }

    private static class TLObjectDeserializer implements JsonSerializer<TLObject> {
        @Override
        public JsonElement serialize(TLObject src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObj = new JsonObject();
            String className = src.getClass().getName();
            final String usualPrefix = "org.telegram.tgnet.";
            if (className.startsWith(usualPrefix)) {
                className = className.substring(usualPrefix.length());
            }
            jsonObj.addProperty("_", className);
            try {
                Field[] fields = src.getClass().getFields();
                for (Field field : fields) {
                    if (privateFields != null && privateFields.contains(field.getName())) continue;
                    field.setAccessible(true);
                    try {
                        Object value = field.get(src);
                        if (value != null) {
                            Class clazz = value.getClass();
                            if (clazz.isInstance(DispatchQueue.class) || clazz.isInstance(AnimatedFileDrawable.class) || clazz.isInstance(ColorStateList.class) || clazz.isInstance(Context.class)) {
                                continue;
                            }
                        }
                        JsonElement jsonElement = context.serialize(value);
                        jsonObj.add(field.getName(), jsonElement);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return jsonObj;
        }
    }

    public void init() {
        if (initied) {
            return;
        }
        dateFormat = FastDateFormat.getInstance("yyyy_MM_dd-HH_mm_ss.SSS", Locale.US);
        fileDateFormat = FastDateFormat.getInstance("yyyy_MM_dd-HH_mm_ss", Locale.US);
        String date = fileDateFormat.format(System.currentTimeMillis());
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return;
            }
            currentFile = new File(dir, date + ".txt");
            tlRequestsFile = new File(dir, date + "_mtproto.txt");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            logQueue = new DispatchQueue("logQueue");
            currentFile.createNewFile();
            FileOutputStream stream = new FileOutputStream(currentFile);
            streamWriter = new OutputStreamWriter(stream);
            streamWriter.write("-----start log " + date + "-----\n");
            streamWriter.flush();

            FileOutputStream tlStream = new FileOutputStream(tlRequestsFile);
            tlStreamWriter = new OutputStreamWriter(tlStream);
            tlStreamWriter.write("-----start log " + date + "-----\n");
            tlStreamWriter.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (BuildVars.DEBUG_VERSION) {
            new ANRDetector(this::dumpANR);
        }
        initied = true;
    }

    public static void ensureInitied() {
        getInstance().init();
    }

    public static String getNetworkLogPath() {
        if (!BuildVars.LOGS_ENABLED) {
            return "";
        }
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return "";
            }
            getInstance().networkFile = new File(dir, getInstance().fileDateFormat.format(System.currentTimeMillis()) + "_net.txt");
            return getInstance().networkFile.getAbsolutePath();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getTonlibLogPath() {
        if (!BuildVars.LOGS_ENABLED) {
            return "";
        }
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return "";
            }
            getInstance().tonlibFile = new File(dir, getInstance().dateFormat.format(System.currentTimeMillis()) + "_tonlib.txt");
            return getInstance().tonlibFile.getAbsolutePath();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return "";
    }

    public static void e(final String message, final Throwable exception) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        String tag = mkTag();
        Log.e(tag, message, exception);
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/" + tag + ": " + message + "\n");
                    getInstance().streamWriter.write(exception.toString());
                    StackTraceElement[] stack = exception.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public static void e(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        String tag = mkTag();
        Log.e(tag, message);
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/" + tag + ": " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public static void e(final Throwable e) {
        e(e, true);
    }

    public static void e(final Throwable e, boolean logToAppCenter) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
//        if (BuildVars.DEBUG_VERSION && needSent(e) && logToAppCenter) {
//            AndroidUtilities.appCenterLog(e);
//        }
        if (BuildVars.DEBUG_VERSION && e.getMessage() != null && e.getMessage().contains("disk image is malformed") && !databaseIsMalformed) {
            FileLog.d("copy malformed files");
            databaseIsMalformed = true;
            File filesDir = ApplicationLoader.getFilesDirFixed();
            filesDir = new File(filesDir, "malformed_database/");
            filesDir.mkdirs();
            ArrayList<File> malformedFiles = MessagesStorage.getInstance(UserConfig.selectedAccount).getDatabaseFiles();
            for (int i = 0; i < malformedFiles.size(); i++) {
                try {
                    AndroidUtilities.copyFile(malformedFiles.get(i), new File(filesDir, malformedFiles.get(i).getName()));
                } catch (IOException ex) {
                    FileLog.e(ex);
                }
            }
        }
        final String tag = mkTag();
        Log.e(tag, mkMessage(e));
        ensureInitied();
        e.printStackTrace();
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/" + tag + ": " + e + "\n");
                    StackTraceElement[] stack = e.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                    }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: Caused by " + cause + "\n");
                        stack = cause.getStackTrace();
                        for (int a = 0; a < stack.length; a++) {
                            getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                        }
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            });
        } else {
            e.printStackTrace();
        }
    }

    public static void fatal(final Throwable e) {
        fatal(e, true);
    }

    private static long dumpedHeap;
    private void dumpMemory() {
        if (System.currentTimeMillis() - dumpedHeap < 30_000) return;
        dumpedHeap = System.currentTimeMillis();
        try {
            Debug.dumpHprofData(new File(AndroidUtilities.getLogsDir(), getInstance().dateFormat.format(System.currentTimeMillis()) + "_heap.hprof").getAbsolutePath());
        } catch (Exception e2) {
            FileLog.e(e2);
        }
    }

    private void dumpANR() {
        StringBuilder sb = new StringBuilder();
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

        for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stackTrace = entry.getValue();

            sb.append("Thread: ").append(thread.getName()).append("\n");
            for (StackTraceElement element : stackTrace) {
                sb.append("\tat ").append(element).append("\n");
            }
            sb.append("\n\n");
        }

        FileLog.e("ANR thread dump\n" + sb.toString());
        dumpMemory();
    }

    public static void fatal(final Throwable e, boolean logToAppCenter) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        if (e instanceof OutOfMemoryError) {
            getInstance().dumpMemory();
        }
//        if (logToAppCenter && BuildVars.DEBUG_VERSION && needSent(e)) {
//            AndroidUtilities.appCenterLog(e);
//        }
        ensureInitied();
        e.printStackTrace();
        String tag = mkTag();
        Log.e(tag, mkMessage(e));
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " FATAL/tmessages: " + e + "\n");
                    StackTraceElement[] stack = e.getStackTrace();
                    for (int a = 0; a < stack.length; a++) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " FATAL/tmessages: \tat " + stack[a] + "\n");
                    }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: Caused by " + cause + "\n");
                        stack = cause.getStackTrace();
                        for (int a = 0; a < stack.length; a++) {
                            getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " E/tmessages: \tat " + stack[a] + "\n");
                        }
                    }
                    getInstance().streamWriter.flush();
                } catch (Exception e1) {
                    e1.printStackTrace();
                }

                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                    System.exit(2);
                }
            });
        } else {
            e.printStackTrace();
            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                System.exit(2);
            }
        }
    }

    private static String mkTag() {
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String className = stackTrace[4].getClassName();
        int lastDotIndex = className.lastIndexOf(".");
        return lastDotIndex != -1 ? className.substring(lastDotIndex + 1) : className;

    }

    private static String mkMessage(Throwable e) {
        String message = e.getMessage();
        if (message != null) return e.getClass().getSimpleName() + ": " + message;
        return e.getClass().getSimpleName();
    }

    private static boolean needSent(Throwable e) {
        if (e instanceof InterruptedException || e instanceof MediaCodecVideoConvertor.ConversionCanceledException || e instanceof IgnoreSentException) {
            return false;
        }
        return true;
    }

    public static void d(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        String tag = mkTag();
//        Log.d(tag, message);
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " D/" + tag + ": " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                    if (AndroidUtilities.isENOSPC(e)) {
                        LaunchActivity.checkFreeDiscSpaceStatic(1);
                    }
                }
            });
        }
    }

    public static void w(final String message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        ensureInitied();
        String tag = mkTag();
        Log.w(tag, message);
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(() -> {
                try {
                    getInstance().streamWriter.write(getInstance().dateFormat.format(System.currentTimeMillis()) + " W/" + tag + ": " + message + "\n");
                    getInstance().streamWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public static void cleanupLogs() {
        ensureInitied();
        File dir = AndroidUtilities.getLogsDir();
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (int a = 0; a < files.length; a++) {
                File file = files[a];
                if (getInstance().currentFile != null && file.getAbsolutePath().equals(getInstance().currentFile.getAbsolutePath())) {
                    continue;
                }
                if (getInstance().networkFile != null && file.getAbsolutePath().equals(getInstance().networkFile.getAbsolutePath())) {
                    continue;
                }
                if (getInstance().tonlibFile != null && file.getAbsolutePath().equals(getInstance().tonlibFile.getAbsolutePath())) {
                    continue;
                }
                file.delete();
            }
        }
    }

    public static class IgnoreSentException extends Exception {

        public IgnoreSentException(String e) {
            super(e);
        }

    }

    public class ANRDetector {
        private final long TIMEOUT_MS = 5000; // ANR threshold (5 seconds)
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private boolean isUIThreadResponsive = true;

        public ANRDetector(Runnable anrDetected) {
            new Thread(() -> {
                while (true) {
                    isUIThreadResponsive = false;

                    // Post a task to the main thread
                    mainHandler.post(() -> isUIThreadResponsive = true);

                    try {
                        Thread.sleep(TIMEOUT_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    if (!isUIThreadResponsive) {
                        anrDetected.run();
                    }
                }
            }).start();
        }
    }
}
