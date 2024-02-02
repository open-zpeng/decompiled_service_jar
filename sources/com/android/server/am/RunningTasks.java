package com.android.server.am;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.util.SparseArray;
import com.android.server.am.TaskRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RunningTasks {
    private static final Comparator<TaskRecord> LAST_ACTIVE_TIME_COMPARATOR = new Comparator() { // from class: com.android.server.am.-$$Lambda$RunningTasks$BGar3HlUsTw-0HzSmfkEWly0moY
        @Override // java.util.Comparator
        public final int compare(Object obj, Object obj2) {
            int signum;
            signum = Long.signum(((TaskRecord) obj2).lastActiveTime - ((TaskRecord) obj).lastActiveTime);
            return signum;
        }
    };
    private final TaskRecord.TaskActivitiesReport mTmpReport = new TaskRecord.TaskActivitiesReport();
    private final TreeSet<TaskRecord> mTmpSortedSet = new TreeSet<>(LAST_ACTIVE_TIME_COMPARATOR);
    private final ArrayList<TaskRecord> mTmpStackTasks = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, SparseArray<ActivityDisplay> activityDisplays, int callingUid, boolean allowed) {
        if (maxNum <= 0) {
            return;
        }
        this.mTmpSortedSet.clear();
        this.mTmpStackTasks.clear();
        int numDisplays = activityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = activityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.getRunningTasks(this.mTmpStackTasks, ignoreActivityType, ignoreWindowingMode, callingUid, allowed);
                for (int i = this.mTmpStackTasks.size() - 1; i >= 0; i--) {
                    this.mTmpSortedSet.addAll(this.mTmpStackTasks);
                }
            }
        }
        Iterator<TaskRecord> iter = this.mTmpSortedSet.iterator();
        for (int maxNum2 = maxNum; iter.hasNext() && maxNum2 != 0; maxNum2--) {
            TaskRecord task = iter.next();
            list.add(createRunningTaskInfo(task));
        }
    }

    private ActivityManager.RunningTaskInfo createRunningTaskInfo(TaskRecord task) {
        task.getNumRunningActivities(this.mTmpReport);
        ActivityManager.RunningTaskInfo ci = new ActivityManager.RunningTaskInfo();
        ci.id = task.taskId;
        ci.stackId = task.getStackId();
        ci.baseActivity = this.mTmpReport.base.intent.getComponent();
        ci.topActivity = this.mTmpReport.top.intent.getComponent();
        ci.lastActiveTime = task.lastActiveTime;
        ci.description = task.lastDescription;
        ci.numActivities = this.mTmpReport.numActivities;
        ci.numRunning = this.mTmpReport.numRunning;
        ci.supportsSplitScreenMultiWindow = task.supportsSplitScreenWindowingMode();
        ci.resizeMode = task.mResizeMode;
        ci.configuration.setTo(task.getConfiguration());
        return ci;
    }
}
