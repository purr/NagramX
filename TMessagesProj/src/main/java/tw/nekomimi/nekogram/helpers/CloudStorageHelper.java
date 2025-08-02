package tw.nekomimi.nekogram.helpers;

import android.util.SparseArray;

import com.google.gson.Gson;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;

import java.util.HashMap;

import tw.nekomimi.nekogram.NagramXConfig;

public class CloudStorageHelper extends AccountInstance {

    private static final SparseArray<CloudStorageHelper> Instance = new SparseArray<>();
    private static final long WEBVIEW_BOT_ID = NagramXConfig.WEBVIEW_BOT_ID;
    private static final String WEBVIEW_BOT = NagramXConfig.WEBVIEW_BOT_USERNAME;

    private final Gson gson = new Gson();

    public CloudStorageHelper(int num) {
        super(num);
    }

    public static CloudStorageHelper getInstance(int num) {
        CloudStorageHelper localInstance = Instance.get(num);
        if (localInstance == null) {
            synchronized (CloudStorageHelper.class) {
                localInstance = Instance.get(num);
                if (localInstance == null) {
                    Instance.put(num, localInstance = new CloudStorageHelper(num));
                }
            }
        }
        return localInstance;
    }

    private void invokeWebViewCustomMethod(String method, String data, Utilities.Callback2<String, String> callback) {
        invokeWebViewCustomMethod(method, data, true, callback);
    }

    private void invokeWebViewCustomMethod(String method, String data, boolean searchUser, Utilities.Callback2<String, String> callback) {
        TLRPC.User user = getMessagesController().getUser(WEBVIEW_BOT_ID);
        if (user == null) {
            if (searchUser) {
                getUserHelper().resolveUser(WEBVIEW_BOT, WEBVIEW_BOT_ID, arg -> invokeWebViewCustomMethod(method, data, false, callback));
            } else {
                callback.run(null, "USER_NOT_FOUND");
            }
            return;
        }
        TL_bots.invokeWebViewCustomMethod req = new TL_bots.invokeWebViewCustomMethod();
        req.bot = getMessagesController().getInputUser(user);
        req.custom_method = method;
        req.params = new TLRPC.TL_dataJSON();
        req.params.data = data;
        getConnectionsManager().sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                if (error != null) {
                    callback.run(null, error.text);
                } else if (res instanceof TLRPC.TL_dataJSON) {
                    callback.run(((TLRPC.TL_dataJSON) res).data, null);
                } else {
                    callback.run(null, null);
                }
            }
        }));
    }

    public void setItem(String key, String value, Utilities.Callback2<String, String> callback) {
        HashMap<String, String> map = new HashMap<>();
        map.put("key", key);
        map.put("value", value);
        invokeWebViewCustomMethod("saveStorageValue", gson.toJson(map), callback);
    }

    public void getItem(String key, Utilities.Callback2<String, String> callback) {
        getItems(new String[]{key}, (res, error) -> {
            if (error == null) {
                callback.run(res.get(key), null);
            } else {
                callback.run(null, error);
            }
        });
    }

    public void getItems(String[] keys, Utilities.Callback2<HashMap<String, String>, String> callback) {
        HashMap<String, String[]> map = new HashMap<>();
        map.put("keys", keys);
        invokeWebViewCustomMethod("getStorageValues", gson.toJson(map), (res, error) -> {
            if (error == null) {
                //noinspection unchecked
                callback.run(gson.fromJson(res, HashMap.class), null);
            } else {
                callback.run(null, error);
            }
        });
    }

    public void removeItem(String key, Utilities.Callback2<String, String> callback) {
        removeItems(new String[]{key}, callback);
    }

    public void removeItems(String[] keys, Utilities.Callback2<String, String> callback) {
        HashMap<String, String[]> map = new HashMap<>();
        map.put("keys", keys);
        invokeWebViewCustomMethod("deleteStorageValues", gson.toJson(map), callback);
    }

    public void getKeys(Utilities.Callback2<String[], String> callback) {
        invokeWebViewCustomMethod("getStorageKeys", "{}", (res, error) -> {
            if (error == null) {
                String[] keys = gson.fromJson(res, String[].class);
                callback.run(keys, null);
            } else {
                callback.run(null, error);
            }
        });
    }
}
