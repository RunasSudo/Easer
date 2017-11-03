package ryey.easer.plugins.operation.broadcast;

import android.net.Uri;

import java.util.List;

public class IntentData {
    String action;
    List<String> category;
    String type;
    Uri data;

    @Override
    public String toString() {
        return String.format("action:%s category:%s type:%s data:%s", action, category, type, data);
    }
}
