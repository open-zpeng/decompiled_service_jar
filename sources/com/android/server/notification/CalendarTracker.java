package com.android.server.notification;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.service.notification.ZenModeConfig;
import android.util.ArraySet;
import android.util.Log;
import java.io.PrintWriter;
import java.util.Objects;

/* loaded from: classes.dex */
public class CalendarTracker {
    private static final String ATTENDEE_SELECTION = "event_id = ? AND attendeeEmail = ?";
    private static final boolean DEBUG_ATTENDEES = false;
    private static final int EVENT_CHECK_LOOKAHEAD = 86400000;
    private static final String INSTANCE_ORDER_BY = "begin ASC";
    private static final String TAG = "ConditionProviders.CT";
    private Callback mCallback;
    private final ContentObserver mObserver = new ContentObserver(null) { // from class: com.android.server.notification.CalendarTracker.1
        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri u) {
            if (CalendarTracker.DEBUG) {
                Log.d(CalendarTracker.TAG, "onChange selfChange=" + selfChange + " uri=" + u + " u=" + CalendarTracker.this.mUserContext.getUserId());
            }
            CalendarTracker.this.mCallback.onChanged();
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            if (CalendarTracker.DEBUG) {
                Log.d(CalendarTracker.TAG, "onChange selfChange=" + selfChange);
            }
        }
    };
    private boolean mRegistered;
    private final Context mSystemContext;
    private final Context mUserContext;
    private static final boolean DEBUG = Log.isLoggable("ConditionProviders", 3);
    private static final String[] INSTANCE_PROJECTION = {"begin", "end", "title", "visible", "event_id", "calendar_displayName", "ownerAccount", "calendar_id", "availability"};
    private static final String[] ATTENDEE_PROJECTION = {"event_id", "attendeeEmail", "attendeeStatus"};

    /* loaded from: classes.dex */
    public interface Callback {
        void onChanged();
    }

    /* loaded from: classes.dex */
    public static class CheckEventResult {
        public boolean inEvent;
        public long recheckAt;
    }

    public CalendarTracker(Context systemContext, Context userContext) {
        this.mSystemContext = systemContext;
        this.mUserContext = userContext;
    }

    public void setCallback(Callback callback) {
        if (this.mCallback == callback) {
            return;
        }
        this.mCallback = callback;
        setRegistered(this.mCallback != null);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("mCallback=");
        pw.println(this.mCallback);
        pw.print(prefix);
        pw.print("mRegistered=");
        pw.println(this.mRegistered);
        pw.print(prefix);
        pw.print("u=");
        pw.println(this.mUserContext.getUserId());
    }

    private ArraySet<Long> getCalendarsWithAccess() {
        long start = System.currentTimeMillis();
        ArraySet<Long> rt = new ArraySet<>();
        String[] projection = {"_id"};
        Cursor cursor = null;
        try {
            cursor = this.mUserContext.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, projection, "calendar_access_level >= 500 AND sync_events = 1", null, null);
            while (cursor != null) {
                if (!cursor.moveToNext()) {
                    break;
                }
                rt.add(Long.valueOf(cursor.getLong(0)));
            }
            if (DEBUG) {
                Log.d(TAG, "getCalendarsWithAccess took " + (System.currentTimeMillis() - start));
            }
            return rt;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:46:0x0142, code lost:
        if (java.util.Objects.equals(r35.calName, r9) == false) goto L80;
     */
    /* JADX WARN: Code restructure failed: missing block: B:88:0x01c4, code lost:
        if (r18 != null) goto L90;
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x01c6, code lost:
        r18.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:95:0x01df, code lost:
        if (r18 == null) goto L88;
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x01e2, code lost:
        return r0;
     */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0154  */
    /* JADX WARN: Removed duplicated region for block: B:57:0x015a A[ADDED_TO_REGION] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public com.android.server.notification.CalendarTracker.CheckEventResult checkEvent(android.service.notification.ZenModeConfig.EventInfo r35, long r36) {
        /*
            Method dump skipped, instructions count: 490
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.CalendarTracker.checkEvent(android.service.notification.ZenModeConfig$EventInfo, long):com.android.server.notification.CalendarTracker$CheckEventResult");
    }

    /* JADX WARN: Multi-variable type inference failed */
    private boolean meetsAttendee(ZenModeConfig.EventInfo filter, int eventId, String email) {
        String[] selectionArgs;
        String selection;
        int i;
        StringBuilder sb;
        long start = System.currentTimeMillis();
        String selection2 = ATTENDEE_SELECTION;
        int i2 = 2;
        int i3 = 0;
        int i4 = 1;
        String[] selectionArgs2 = {Integer.toString(eventId), email};
        Cursor cursor = this.mUserContext.getContentResolver().query(CalendarContract.Attendees.CONTENT_URI, ATTENDEE_PROJECTION, ATTENDEE_SELECTION, selectionArgs2, null);
        try {
            if (cursor != null) {
                try {
                    if (cursor.getCount() != 0) {
                        boolean rt = 0;
                        while (cursor.moveToNext()) {
                            long rowEventId = cursor.getLong(i3);
                            String rowEmail = cursor.getString(i4);
                            int status = cursor.getInt(i2);
                            boolean meetsReply = meetsReply(filter.reply, status);
                            if (DEBUG) {
                                selectionArgs = selectionArgs2;
                                try {
                                    sb = new StringBuilder();
                                    selection = selection2;
                                } catch (Throwable th) {
                                    th = th;
                                }
                                try {
                                    sb.append("");
                                    i = 0;
                                    sb.append(String.format("status=%s, meetsReply=%s", attendeeStatusToString(status), Boolean.valueOf(meetsReply)));
                                    Log.d(TAG, sb.toString());
                                } catch (Throwable th2) {
                                    th = th2;
                                    if (cursor != null) {
                                        cursor.close();
                                    }
                                    if (DEBUG) {
                                        Log.d(TAG, "meetsAttendee took " + (System.currentTimeMillis() - start));
                                    }
                                    throw th;
                                }
                            } else {
                                selectionArgs = selectionArgs2;
                                selection = selection2;
                                i = 0;
                            }
                            int i5 = rt | ((rowEventId == ((long) eventId) && Objects.equals(rowEmail, email) && meetsReply) ? 1 : i);
                            selectionArgs2 = selectionArgs;
                            i3 = i;
                            selection2 = selection;
                            i2 = 2;
                            i4 = 1;
                            rt = i5;
                        }
                        cursor.close();
                        if (DEBUG) {
                            Log.d(TAG, "meetsAttendee took " + (System.currentTimeMillis() - start));
                        }
                        return rt;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
            if (DEBUG) {
                Log.d(TAG, "No attendees found");
            }
            if (cursor != null) {
                cursor.close();
            }
            if (DEBUG) {
                Log.d(TAG, "meetsAttendee took " + (System.currentTimeMillis() - start));
                return true;
            }
            return true;
        } catch (Throwable th4) {
            th = th4;
        }
    }

    private void setRegistered(boolean registered) {
        if (this.mRegistered == registered) {
            return;
        }
        ContentResolver cr = this.mSystemContext.getContentResolver();
        int userId = this.mUserContext.getUserId();
        if (this.mRegistered) {
            if (DEBUG) {
                Log.d(TAG, "unregister content observer u=" + userId);
            }
            cr.unregisterContentObserver(this.mObserver);
        }
        this.mRegistered = registered;
        if (DEBUG) {
            Log.d(TAG, "mRegistered = " + registered + " u=" + userId);
        }
        if (this.mRegistered) {
            if (DEBUG) {
                Log.d(TAG, "register content observer u=" + userId);
            }
            cr.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, this.mObserver, userId);
            cr.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, this.mObserver, userId);
            cr.registerContentObserver(CalendarContract.Calendars.CONTENT_URI, true, this.mObserver, userId);
        }
    }

    private static String attendeeStatusToString(int status) {
        if (status != 0) {
            if (status != 1) {
                if (status != 2) {
                    if (status != 3) {
                        if (status == 4) {
                            return "ATTENDEE_STATUS_TENTATIVE";
                        }
                        return "ATTENDEE_STATUS_UNKNOWN_" + status;
                    }
                    return "ATTENDEE_STATUS_INVITED";
                }
                return "ATTENDEE_STATUS_DECLINED";
            }
            return "ATTENDEE_STATUS_ACCEPTED";
        }
        return "ATTENDEE_STATUS_NONE";
    }

    private static String availabilityToString(int availability) {
        if (availability != 0) {
            if (availability != 1) {
                if (availability == 2) {
                    return "AVAILABILITY_TENTATIVE";
                }
                return "AVAILABILITY_UNKNOWN_" + availability;
            }
            return "AVAILABILITY_FREE";
        }
        return "AVAILABILITY_BUSY";
    }

    private static boolean meetsReply(int reply, int attendeeStatus) {
        return reply != 0 ? reply != 1 ? reply == 2 && attendeeStatus == 1 : attendeeStatus == 1 || attendeeStatus == 4 : attendeeStatus != 2;
    }
}
