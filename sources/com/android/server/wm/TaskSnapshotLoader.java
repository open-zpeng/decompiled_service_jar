package com.android.server.wm;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.util.Slog;
import com.android.server.wm.nano.WindowManagerProtos;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
/* loaded from: classes.dex */
class TaskSnapshotLoader {
    private static final String TAG = "WindowManager";
    private final TaskSnapshotPersister mPersister;

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskSnapshotLoader(TaskSnapshotPersister persister) {
        this.mPersister = persister;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.TaskSnapshot loadTask(int taskId, int userId, boolean reducedResolution) {
        File bitmapFile;
        File protoFile = this.mPersister.getProtoFile(taskId, userId);
        if (reducedResolution) {
            bitmapFile = this.mPersister.getReducedResolutionBitmapFile(taskId, userId);
        } else {
            bitmapFile = this.mPersister.getBitmapFile(taskId, userId);
        }
        File bitmapFile2 = bitmapFile;
        if (bitmapFile2 != null && protoFile.exists()) {
            if (bitmapFile2.exists()) {
                try {
                    byte[] bytes = Files.readAllBytes(protoFile.toPath());
                    WindowManagerProtos.TaskSnapshotProto proto = WindowManagerProtos.TaskSnapshotProto.parseFrom(bytes);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.HARDWARE;
                    Bitmap bitmap = BitmapFactory.decodeFile(bitmapFile2.getPath(), options);
                    try {
                        if (bitmap == null) {
                            Slog.w(TAG, "Failed to load bitmap: " + bitmapFile2.getPath());
                            return null;
                        }
                        GraphicBuffer buffer = bitmap.createGraphicBufferHandle();
                        if (buffer == null) {
                            Slog.w(TAG, "Failed to retrieve gralloc buffer for bitmap: " + bitmapFile2.getPath());
                            return null;
                        }
                        try {
                            return new ActivityManager.TaskSnapshot(buffer, proto.orientation, new Rect(proto.insetLeft, proto.insetTop, proto.insetRight, proto.insetBottom), reducedResolution, reducedResolution ? TaskSnapshotPersister.REDUCED_SCALE : 1.0f, proto.isRealSnapshot, proto.windowingMode, proto.systemUiVisibility, proto.isTranslucent);
                        } catch (IOException e) {
                            Slog.w(TAG, "Unable to load task snapshot data for taskId=" + taskId);
                            return null;
                        }
                    } catch (IOException e2) {
                    }
                } catch (IOException e3) {
                }
            }
        }
        return null;
    }
}
