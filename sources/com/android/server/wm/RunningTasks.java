package com.android.server.wm;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.util.ArraySet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RunningTasks {
    private static final Comparator<TaskRecord> LAST_ACTIVE_TIME_COMPARATOR = new Comparator() { // from class: com.android.server.wm.-$$Lambda$RunningTasks$B8bQN-i7MO0XIePhmkVnejRGNp0
        @Override // java.util.Comparator
        public final int compare(Object obj, Object obj2) {
            int signum;
            signum = Long.signum(((TaskRecord) obj2).lastActiveTime - ((TaskRecord) obj).lastActiveTime);
            return signum;
        }
    };
    private final TreeSet<TaskRecord> mTmpSortedSet = new TreeSet<>(LAST_ACTIVE_TIME_COMPARATOR);
    private final ArrayList<TaskRecord> mTmpStackTasks = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, ArrayList<ActivityDisplay> activityDisplays, int callingUid, boolean allowed, boolean crossUser, ArraySet<Integer> profileIds) {
        if (maxNum <= 0) {
            return;
        }
        this.mTmpSortedSet.clear();
        int numDisplays = activityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = activityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                this.mTmpStackTasks.clear();
                stack.getRunningTasks(this.mTmpStackTasks, ignoreActivityType, ignoreWindowingMode, callingUid, allowed, crossUser, profileIds);
                this.mTmpSortedSet.addAll(this.mTmpStackTasks);
            }
        }
        Iterator<TaskRecord> iter = this.mTmpSortedSet.iterator();
        for (int maxNum2 = maxNum; iter.hasNext() && maxNum2 != 0; maxNum2--) {
            TaskRecord task = iter.next();
            list.add(createRunningTaskInfo(task));
        }
    }

    private ActivityManager.RunningTaskInfo createRunningTaskInfo(TaskRecord task) {
        ActivityManager.RunningTaskInfo rti = new ActivityManager.RunningTaskInfo();
        task.fillTaskInfo(rti);
        rti.id = rti.taskId;
        return rti;
    }
}
