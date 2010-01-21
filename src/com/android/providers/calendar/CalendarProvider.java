/*
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** See the License for the specific language governing permissions and
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** limitations under the License.
*/

package com.android.providers.calendar;

import com.google.android.collect.Maps;
import com.google.android.collect.Sets;
import com.google.android.common.gdata.AndroidGDataClient;
import com.google.android.common.gdata.AndroidXmlParserFactory;
import com.google.android.gsf.GoogleLoginServiceConstants;
import com.google.android.gsf.AbstractGDataSyncAdapter;
import com.google.android.gsf.AbstractGDataSyncAdapter.GDataSyncData;
import com.google.wireless.gdata.calendar.client.CalendarClient;
import com.google.wireless.gdata.calendar.parser.xml.XmlCalendarGDataParserFactory;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlarmManager;
import android.app.PendingIntent;
import com.google.android.gsf.AbstractSyncableContentProvider;
import com.google.android.gsf.AbstractTableMerger;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.pim.DateException;
import android.pim.RecurrenceSet;
import android.provider.Calendar;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.CalendarAlerts;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.provider.Calendar.ExtendedProperties;
import android.provider.Calendar.Instances;
import android.provider.Calendar.Reminders;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Config;
import android.util.Log;
import android.util.TimeFormatException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class CalendarProvider extends AbstractSyncableContentProvider {
    private boolean DEBUG = true;
    private static final boolean PROFILE = false;
    private static final boolean MULTIPLE_ATTENDEES_PER_EVENT = true;

    private static final String INVALID_CALENDARALERTS_SELECTOR =
        "_id IN (SELECT ca._id FROM CalendarAlerts AS ca"
            + " LEFT OUTER JOIN Instances USING (event_id, begin, end)"
            + " LEFT OUTER JOIN Reminders AS r ON (ca.event_id=r.event_id AND ca.minutes=r.minutes)"
            + " WHERE Instances.begin ISNULL OR ca.alarmTime<?"
            + "   OR (r.minutes ISNULL AND ca.minutes<>0))";

    private static final String[] ACCOUNTS_PROJECTION =
            new String[] {Calendars._SYNC_ACCOUNT, Calendars._SYNC_ACCOUNT_TYPE};

    private static final String[] EVENTS_PROJECTION = new String[] {
            Events._SYNC_ID,
            Events._SYNC_VERSION,
            Events._SYNC_ACCOUNT,
            Events._SYNC_ACCOUNT_TYPE,
            Events.CALENDAR_ID,
            Events.RRULE,
            Events.RDATE,
            Events.ORIGINAL_EVENT,
    };
    private static final int EVENTS_SYNC_ID_INDEX = 0;
    private static final int EVENTS_SYNC_VERSION_INDEX = 1;
    private static final int EVENTS_SYNC_ACCOUNT_NAME_INDEX = 2;
    private static final int EVENTS_SYNC_ACCOUNT_TYPE_INDEX = 3;
    private static final int EVENTS_CALENDAR_ID_INDEX = 4;
    private static final int EVENTS_RRULE_INDEX = 5;
    private static final int EVENTS_RDATE_INDEX = 6;
    private static final int EVENTS_ORIGINAL_EVENT_INDEX = 7;

    private DatabaseUtils.InsertHelper mCalendarsInserter;
    private DatabaseUtils.InsertHelper mEventsInserter;
    private DatabaseUtils.InsertHelper mEventsRawTimesInserter;
    private DatabaseUtils.InsertHelper mDeletedEventsInserter;
    private DatabaseUtils.InsertHelper mInstancesInserter;
    private DatabaseUtils.InsertHelper mAttendeesInserter;
    private DatabaseUtils.InsertHelper mRemindersInserter;
    private DatabaseUtils.InsertHelper mCalendarAlertsInserter;
    private DatabaseUtils.InsertHelper mExtendedPropertiesInserter;

    /**
     * The cached copy of the CalendarMetaData database table.
     * Make this "package private" instead of "private" so that test code
     * can access it.
     */
    MetaData mMetaData;

    // To determine if a recurrence exception originally overlapped the
    // window, we need to assume a maximum duration, since we only know
    // the original start time.
    private static final int MAX_ASSUMED_DURATION = 7*24*60*60*1000;

    public static final class TimeRange {
        public long begin;
        public long end;
        public boolean allDay;
    }

    public static final class InstancesRange {
        public long begin;
        public long end;

        public InstancesRange(long begin, long end) {
            this.begin = begin;
            this.end = end;
        }
    }

    public static final class InstancesList
            extends ArrayList<ContentValues> {
    }

    public static final class EventInstancesMap
            extends HashMap<String, InstancesList> {
        public void add(String syncId, ContentValues values) {
            InstancesList instances = get(syncId);
            if (instances == null) {
                instances = new InstancesList();
                put(syncId, instances);
            }
            instances.add(values);
        }
    }

    // A thread that runs in the background and schedules the next
    // calendar event alarm.
    private class AlarmScheduler extends Thread {
        boolean mRemoveAlarms;

        public AlarmScheduler(boolean removeAlarms) {
            mRemoveAlarms = removeAlarms;
        }

        public void run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runScheduleNextAlarm(mRemoveAlarms);
            } catch (SQLException e) {
                Log.e(TAG, "runScheduleNextAlarm() failed", e);
            }
        }
    }

    /**
     * We search backward in time for event reminders that we may have missed
     * and schedule them if the event has not yet expired.  The amount in
     * the past to search backwards is controlled by this constant.  It
     * should be at least a few minutes to allow for an event that was
     * recently created on the web to make its way to the phone.  Two hours
     * might seem like overkill, but it is useful in the case where the user
     * just crossed into a new timezone and might have just missed an alarm.
     */
    private static final long SCHEDULE_ALARM_SLACK = 2 * android.text.format.DateUtils.HOUR_IN_MILLIS;

    /**
     * Alarms older than this threshold will be deleted from the CalendarAlerts
     * table.  This should be at least a day because if the timezone is
     * wrong and the user corrects it we might delete good alarms that
     * appear to be old because the device time was incorrectly in the future.
     * This threshold must also be larger than SCHEDULE_ALARM_SLACK.  We add
     * the SCHEDULE_ALARM_SLACK to ensure this.
     *
     * To make it easier to find and debug problems with missed reminders,
     * set this to something greater than a day.
     */
    private static final long CLEAR_OLD_ALARM_THRESHOLD =
            7 * android.text.format.DateUtils.DAY_IN_MILLIS + SCHEDULE_ALARM_SLACK;

    // A lock for synchronizing access to fields that are shared
    // with the AlarmScheduler thread.
    private Object mAlarmLock = new Object();

    private static final String TAG = "CalendarProvider";
    private static final String DATABASE_NAME = "calendar.db";

    // Note: if you update the version number, you must also update the code
    // in upgradeDatabase() to modify the database (gracefully, if possible).
    private static final int DATABASE_VERSION = 59;

    // Make sure we load at least two months worth of data.
    // Client apps can load more data in a background thread.
    private static final long MINIMUM_EXPANSION_SPAN =
            2L * 31 * 24 * 60 * 60 * 1000;

    private static final String[] sCalendarsIdProjection = new String[] { Calendars._ID };
    private static final int CALENDARS_INDEX_ID = 0;

    // Allocate the string constant once here instead of on the heap
    private static final String CALENDAR_ID_SELECTION = "calendar_id=?";

    private static final String[] sInstancesProjection =
            new String[] { Instances.START_DAY, Instances.END_DAY,
                    Instances.START_MINUTE, Instances.END_MINUTE, Instances.ALL_DAY };

    private static final int INSTANCES_INDEX_START_DAY = 0;
    private static final int INSTANCES_INDEX_END_DAY = 1;
    private static final int INSTANCES_INDEX_START_MINUTE = 2;
    private static final int INSTANCES_INDEX_END_MINUTE = 3;
    private static final int INSTANCES_INDEX_ALL_DAY = 4;


    private CalendarClient mCalendarClient = null;

    private AlarmManager mAlarmManager;

    private CalendarAppWidgetProvider mAppWidgetProvider = CalendarAppWidgetProvider.getInstance();

    /**
     * Listens for timezone changes and disk-no-longer-full events
     */
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onReceive() " + action);
            }
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                updateTimezoneDependentFields();
                scheduleNextAlarm(false /* do not remove alarms */);
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                // Try to clean up if things were screwy due to a full disk
                updateTimezoneDependentFields();
                scheduleNextAlarm(false /* do not remove alarms */);
            } else if (Intent.ACTION_TIME_CHANGED.equals(action)) {
                scheduleNextAlarm(false /* do not remove alarms */);
            }
        }
    };

    public CalendarProvider() {
        super(DATABASE_NAME, DATABASE_VERSION, Calendars.CONTENT_URI);
    }

    @Override
    public boolean onCreate() {
        super.onCreate();

        setTempProviderSyncAdapter(new CalendarSyncAdapter(getContext(), this));

        // Register for Intent broadcasts
        IntentFilter filter = new IntentFilter();

        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        final Context c = getContext();

        // We don't ever unregister this because this thread always wants
        // to receive notifications, even in the background.  And if this
        // thread is killed then the whole process will be killed and the
        // memory resources will be reclaimed.
        c.registerReceiver(mIntentReceiver, filter);

        mMetaData = new MetaData(mOpenHelper);
        updateTimezoneDependentFields();

        return true;
    }

    /**
     * This creates a background thread to check the timezone and update
     * the timezone dependent fields in the Instances table if the timezone
     * has changes.
     */
    protected void updateTimezoneDependentFields() {
        Thread thread = new TimezoneCheckerThread();
        thread.start();
    }

    private class TimezoneCheckerThread extends Thread {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                doUpdateTimezoneDependentFields();
            } catch (SQLException e) {
                Log.e(TAG, "doUpdateTimezoneDependentFields() failed", e);
                try {
                    // Clear at least the in-memory data (and if possible the
                    // database fields) to force a re-computation of Instances.
                    mMetaData.clearInstanceRange();
                } catch (SQLException e2) {
                    Log.e(TAG, "clearInstanceRange() also failed: " + e2);
                }
            }
        }
    }

    /**
     * This method runs in a background thread.  If the timezone has changed
     * then the Instances table will be regenerated.
     */
    private void doUpdateTimezoneDependentFields() {
        MetaData.Fields fields = mMetaData.getFields();
        String localTimezone = TimeZone.getDefault().getID();
        if (TextUtils.equals(fields.timezone, localTimezone)) {
            // Even if the timezone hasn't changed, check for missed alarms.
            // This code executes when the CalendarProvider is created and
            // helps to catch missed alarms when the Calendar process is
            // killed (because of low-memory conditions) and then restarted.
            rescheduleMissedAlarms();
            return;
        }

        // The database timezone is different from the current timezone.
        // Regenerate the Instances table for this month.  Include events
        // starting at the beginning of this month.
        long now = System.currentTimeMillis();
        Time time = new Time();
        time.set(now);
        time.monthDay = 1;
        time.hour = 0;
        time.minute = 0;
        time.second = 0;
        long begin = time.normalize(true);
        long end = begin + MINIMUM_EXPANSION_SPAN;
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        handleInstanceQuery(qb, begin, end, new String[] { Instances._ID },
                null /* selection */, null /* sort */, false /* searchByDayInsteadOfMillis */);

        rescheduleMissedAlarms();
    }

    private void rescheduleMissedAlarms() {
        AlarmManager manager = getAlarmManager();
        if (manager != null) {
            Context context = getContext();
            ContentResolver cr = context.getContentResolver();
            CalendarAlerts.rescheduleMissedAlarms(cr, context, manager);
        }
    }

    @Override
    protected void onDatabaseOpened(SQLiteDatabase db) {
        db.markTableSyncable("Events", "DeletedEvents");

        if (!isTemporary()) {
            mCalendarClient = new CalendarClient(
                    new AndroidGDataClient(getContext(), CalendarSyncAdapter.USER_AGENT_APP_VERSION),
                    new XmlCalendarGDataParserFactory(
                            new AndroidXmlParserFactory()));
        }

        mCalendarsInserter = new DatabaseUtils.InsertHelper(db, "Calendars");
        mEventsInserter = new DatabaseUtils.InsertHelper(db, "Events");
        mEventsRawTimesInserter = new DatabaseUtils.InsertHelper(db, "EventsRawTimes");
        mDeletedEventsInserter = new DatabaseUtils.InsertHelper(db, "DeletedEvents");
        mInstancesInserter = new DatabaseUtils.InsertHelper(db, "Instances");
        mAttendeesInserter = new DatabaseUtils.InsertHelper(db, "Attendees");
        mRemindersInserter = new DatabaseUtils.InsertHelper(db, "Reminders");
        mCalendarAlertsInserter = new DatabaseUtils.InsertHelper(db, "CalendarAlerts");
        mExtendedPropertiesInserter =
                new DatabaseUtils.InsertHelper(db, "ExtendedProperties");
    }

    @Override
    protected boolean upgradeDatabase(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading DB from version " + oldVersion
                + " to " + newVersion);
        if (oldVersion < 46) {
            dropTables(db);
            bootstrapDatabase(db);
            return false; // this was lossy
        }

        if (oldVersion == 46) {
            Log.w(TAG, "Upgrading CalendarAlerts table");
            db.execSQL("UPDATE CalendarAlerts SET reminder_id=NULL;");
            db.execSQL("ALTER TABLE CalendarAlerts ADD COLUMN minutes INTEGER DEFAULT 0;");
            oldVersion += 1;
        }

        if (oldVersion == 47) {
            // Changing to version 48 was intended to force a data wipe
            dropTables(db);
            bootstrapDatabase(db);
            return false; // this was lossy
        }

        if (oldVersion == 48) {
            // Changing to version 49 was intended to force a data wipe
            dropTables(db);
            bootstrapDatabase(db);
            return false; // this was lossy
        }

        if (oldVersion == 49) {
            Log.w(TAG, "Upgrading DeletedEvents table");

            // We don't have enough information to fill in the correct
            // value of the calendar_id for old rows in the DeletedEvents
            // table, but rows in that table are transient so it is unlikely
            // that there are any rows.  Plus, the calendar_id is used only
            // when deleting a calendar, which is a rare event.  All new rows
            // will have the correct calendar_id.
            db.execSQL("ALTER TABLE DeletedEvents ADD COLUMN calendar_id INTEGER;");

            // Trigger to remove a calendar's events when we delete the calendar
            db.execSQL("DROP TRIGGER IF EXISTS calendar_cleanup");
            db.execSQL("CREATE TRIGGER calendar_cleanup DELETE ON Calendars " +
                    "BEGIN " +
                    "DELETE FROM Events WHERE calendar_id = old._id;" +
                    "DELETE FROM DeletedEvents WHERE calendar_id = old._id;" +
                    "END");
            db.execSQL("DROP TRIGGER IF EXISTS event_to_deleted");
            oldVersion += 1;
        }

        if (oldVersion == 50) {
            // This should have been deleted in the upgrade from version 49
            // but we missed it.
            db.execSQL("DROP TRIGGER IF EXISTS event_to_deleted");
            oldVersion += 1;
        }

        if (oldVersion == 51) {
            // We added "originalAllDay" to the Events table to keep track of
            // the allDay status of the original recurring event for entries
            // that are exceptions to that recurring event.  We need this so
            // that we can format the date correctly for the "originalInstanceTime"
            // column when we make a change to the recurrence exception and
            // send it to the server.
            db.execSQL("ALTER TABLE Events ADD COLUMN originalAllDay INTEGER;");

            // Iterate through the Events table and for each recurrence
            // exception, fill in the correct value for "originalAllDay",
            // if possible.  The only times where this might not be possible
            // are (1) the original recurring event no longer exists, or
            // (2) the original recurring event does not yet have a _sync_id
            // because it was created on the phone and hasn't been synced to the
            // server yet.  In both cases the originalAllDay field will be set
            // to null.  In the first case we don't care because the recurrence
            // exception will not be displayed and we won't be able to make
            // any changes to it (and even if we did, the server should ignore
            // them, right?).  In the second case, the calendar client already
            // disallows making changes to an instance of a recurring event
            // until the recurring event has been synced to the server so the
            // second case should never occur.

            // "cursor" iterates over all the recurrences exceptions.
            Cursor cursor = db.rawQuery("SELECT _id,originalEvent FROM Events"
                    + " WHERE originalEvent IS NOT NULL", null /* selection args */);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        String originalEvent = cursor.getString(1);

                        // Find the original recurring event (if it exists)
                        Cursor recur = db.rawQuery("SELECT allDay FROM Events"
                                + " WHERE _sync_id=?", new String[] {originalEvent});
                        if (recur == null) {
                            continue;
                        }

                        try {
                            // Fill in the "originalAllDay" field of the
                            // recurrence exception with the "allDay" value
                            // from the recurring event.
                            if (recur.moveToNext()) {
                                int allDay = recur.getInt(0);
                                db.execSQL("UPDATE Events SET originalAllDay=" + allDay
                                        + " WHERE _id="+id);
                            }
                        } finally {
                            recur.close();
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            oldVersion += 1;
        }

        if (oldVersion == 52) {
            Log.w(TAG, "Upgrading CalendarAlerts table");
            db.execSQL("ALTER TABLE CalendarAlerts ADD COLUMN creationTime INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE CalendarAlerts ADD COLUMN receivedTime INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE CalendarAlerts ADD COLUMN notifyTime INTEGER DEFAULT 0;");
            oldVersion += 1;
        }

        if (oldVersion == 53) {
            Log.w(TAG, "adding eventSyncAccountAndIdIndex");
            db.execSQL("CREATE INDEX eventSyncAccountAndIdIndex ON Events ("
                    + Events._SYNC_ACCOUNT + ", " + Events._SYNC_ID + ");");
            oldVersion += 1;
        }

        if (oldVersion == 54) {
            db.execSQL("ALTER TABLE Calendars ADD COLUMN _sync_account_type TEXT;");
            db.execSQL("ALTER TABLE Events ADD COLUMN _sync_account_type TEXT;");
            db.execSQL("ALTER TABLE DeletedEvents ADD COLUMN _sync_account_type TEXT;");
            db.execSQL("UPDATE Calendars"
                    + " SET _sync_account_type='com.google'"
                    + " WHERE _sync_account IS NOT NULL");
            db.execSQL("UPDATE Events"
                    + " SET _sync_account_type='com.google'"
                    + " WHERE _sync_account IS NOT NULL");
            db.execSQL("UPDATE DeletedEvents"
                    + " SET _sync_account_type='com.google'"
                    + " WHERE _sync_account IS NOT NULL");
            Log.w(TAG, "re-creating eventSyncAccountAndIdIndex");
            db.execSQL("DROP INDEX eventSyncAccountAndIdIndex");
            db.execSQL("CREATE INDEX eventSyncAccountAndIdIndex ON Events ("
                    + Events._SYNC_ACCOUNT_TYPE + ", " + Events._SYNC_ACCOUNT + ", "
                    + Events._SYNC_ID + ");");
            oldVersion += 1;
        }
        if (oldVersion == 55 || oldVersion == 56) {  // Both require resync
            // Delete sync state, so all records will be re-synced.
            db.execSQL("DELETE FROM _sync_state;");

            // "cursor" iterates over all the calendars
            Cursor cursor = db.rawQuery("SELECT _sync_account,_sync_account_type,url "
                    + "FROM Calendars",
                    null /* selection args */);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String accountName = cursor.getString(0);
                        String accountType = cursor.getString(1);
                        final Account account = new Account(accountName, accountType);
                        String calendarUrl = cursor.getString(2);
                        scheduleSync(account, false /* two-way sync */, calendarUrl);
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        if (oldVersion == 55) {
            db.execSQL("ALTER TABLE Calendars ADD COLUMN ownerAccount TEXT;");
            db.execSQL("ALTER TABLE Events ADD COLUMN hasAttendeeData INTEGER;");
            // Clear _sync_dirty to avoid a client-to-server sync that could blow away
            // server attendees.
            // Clear _sync_version to pull down the server's event (with attendees)
            // Change the URLs from full-selfattendance to full
            db.execSQL("UPDATE Events"
                    + " SET _sync_dirty=0,"
                    + " _sync_version=NULL,"
                    + " _sync_id="
                    + "REPLACE(_sync_id, '/private/full-selfattendance', '/private/full'),"
                    + " commentsUri ="
                    + "REPLACE(commentsUri, '/private/full-selfattendance', '/private/full');");
            db.execSQL("UPDATE Calendars"
                    + " SET url="
                    + "REPLACE(url, '/private/full-selfattendance', '/private/full');");

            // "cursor" iterates over all the calendars
            Cursor cursor = db.rawQuery("SELECT _id, url FROM Calendars",
                    null /* selection args */);
            // Add the owner column.
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        Long id = cursor.getLong(0);
                        String url = cursor.getString(1);
                        String owner = CalendarSyncAdapter.calendarEmailAddressFromFeedUrl(url);
                        db.execSQL("UPDATE Calendars SET ownerAccount=? WHERE _id=?",
                                new Object[] {owner, id});
                    }
                } finally {
                    cursor.close();
                }
            }
            oldVersion += 1;
        }
        if (oldVersion == 56) {
            db.execSQL("ALTER TABLE Events ADD COLUMN guestsCanModify"
                    + " INTEGER NOT NULL DEFAULT 0;");
            db.execSQL("ALTER TABLE Events ADD COLUMN guestsCanInviteOthers"
                    + " INTEGER NOT NULL DEFAULT 1;");
            db.execSQL("ALTER TABLE Events ADD COLUMN guestsCanSeeGuests"
                    + " INTEGER NOT NULL DEFAULT 1;");
            db.execSQL("ALTER TABLE Events ADD COLUMN organizer STRING;");
            db.execSQL("UPDATE Events SET organizer="
                    + "(SELECT attendeeEmail FROM Attendees WHERE "
                    + "Attendees.event_id = Events._id AND Attendees.attendeeRelationship=2);");


            oldVersion += 1;
        }
        if (oldVersion == 57) {
            db.execSQL("DROP TRIGGER IF EXISTS extended_properties_insert");
            db.execSQL("DROP TRIGGER IF EXISTS extended_properties_delete");
            db.execSQL("CREATE TRIGGER extended_properties_insert INSERT ON ExtendedProperties " +
                    "BEGIN " +
                    "UPDATE Events SET _sync_dirty=1 WHERE Events._id=new.event_id;" +
                    "END");
            db.execSQL("CREATE TRIGGER extended_properties_delete DELETE ON ExtendedProperties " +
                    "BEGIN " +
                    "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                    "END");
            oldVersion += 1;
        }
        if(oldVersion == 58) {
            db.execSQL("DROP TABLE IF EXISTS BusyBits;");
            db.execSQL("CREATE TEMPORARY TABLE CalendarMetaData_Backup" +
                    "(_id,localTimezone,minInstance,maxInstance);");
            db.execSQL("INSERT INTO CalendarMetaData_Backup " +
                    "SELECT _id,localTimezone,minInstance,maxInstance FROM CalendarMetaData;");
            db.execSQL("DROP TABLE CalendarMetaData;");
            db.execSQL("CREATE TABLE CalendarMetaData(_id,localTimezone,minInstance,maxInstance);");
            db.execSQL("INSERT INTO CalendarMetaData " +
                    "SELECT _id,localTimezone,minInstance,maxInstance FROM CalendarMetaData_Backup;");
            db.execSQL("DROP TABLE CalendarMetaData_Backup;");
            oldVersion += 1;
        }

        return true; // this was lossless
    }

    private void dropTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS Calendars;");
        db.execSQL("DROP TABLE IF EXISTS Events;");
        db.execSQL("DROP TABLE IF EXISTS EventsRawTimes;");
        db.execSQL("DROP TABLE IF EXISTS DeletedEvents;");
        db.execSQL("DROP TABLE IF EXISTS Instances;");
        db.execSQL("DROP TABLE IF EXISTS CalendarMetaData;");
        db.execSQL("DROP TABLE IF EXISTS Attendees;");
        db.execSQL("DROP TABLE IF EXISTS Reminders;");
        db.execSQL("DROP TABLE IF EXISTS CalendarAlerts;");
        db.execSQL("DROP TABLE IF EXISTS ExtendedProperties;");
    }

    @Override
    protected void bootstrapDatabase(SQLiteDatabase db) {
        super.bootstrapDatabase(db);
        db.execSQL("CREATE TABLE Calendars (" +
                "_id INTEGER PRIMARY KEY," +
                "_sync_account TEXT," +
                "_sync_account_type TEXT," +
                "_sync_id TEXT," +
                "_sync_version TEXT," +
                "_sync_time TEXT," +            // UTC
                "_sync_local_id INTEGER," +
                "_sync_dirty INTEGER," +
                "_sync_mark INTEGER," + // Used to filter out new rows
                "url TEXT," +
                "name TEXT," +
                "displayName TEXT," +
                "hidden INTEGER NOT NULL DEFAULT 0," +
                "color INTEGER," +
                "access_level INTEGER," +
                "selected INTEGER NOT NULL DEFAULT 1," +
                "sync_events INTEGER NOT NULL DEFAULT 0," +
                "location TEXT," +
                "timezone TEXT," +
                "ownerAccount TEXT" +
                ");");

        // Trigger to remove a calendar's events when we delete the calendar
        db.execSQL("CREATE TRIGGER calendar_cleanup DELETE ON Calendars " +
                "BEGIN " +
                "DELETE FROM Events WHERE calendar_id = old._id;" +
                "DELETE FROM DeletedEvents WHERE calendar_id = old._id;" +
                "END");

        // TODO: do we need both dtend and duration?
        db.execSQL("CREATE TABLE Events (" +
                "_id INTEGER PRIMARY KEY," +
                "_sync_account TEXT," +
                "_sync_account_type TEXT," +
                "_sync_id TEXT," +
                "_sync_version TEXT," +
                "_sync_time TEXT," +            // UTC
                "_sync_local_id INTEGER," +
                "_sync_dirty INTEGER," +
                "_sync_mark INTEGER," + // To filter out new rows
                "calendar_id INTEGER NOT NULL," +
                "htmlUri TEXT," +
                "title TEXT," +
                "eventLocation TEXT," +
                "description TEXT," +
                "eventStatus INTEGER," +
                "selfAttendeeStatus INTEGER NOT NULL DEFAULT 0," +
                "commentsUri TEXT," +
                "dtstart INTEGER," +               // millis since epoch
                "dtend INTEGER," +                 // millis since epoch
                "eventTimezone TEXT," +         // timezone for event
                "duration TEXT," +
                "allDay INTEGER NOT NULL DEFAULT 0," +
                "visibility INTEGER NOT NULL DEFAULT 0," +
                "transparency INTEGER NOT NULL DEFAULT 0," +
                "hasAlarm INTEGER NOT NULL DEFAULT 0," +
                "hasExtendedProperties INTEGER NOT NULL DEFAULT 0," +
                "rrule TEXT," +
                "rdate TEXT," +
                "exrule TEXT," +
                "exdate TEXT," +
                "originalEvent TEXT," +  // _sync_id of recurring event
                "originalInstanceTime INTEGER," +  // millis since epoch
                "originalAllDay INTEGER," +
                "lastDate INTEGER," +               // millis since epoch
                "hasAttendeeData INTEGER NOT NULL DEFAULT 0," +
                "guestsCanModify INTEGER NOT NULL DEFAULT 0," +
                "guestsCanInviteOthers INTEGER NOT NULL DEFAULT 1," +
                "guestsCanSeeGuests INTEGER NOT NULL DEFAULT 1," +
                "organizer STRING" +
                ");");

        db.execSQL("CREATE INDEX eventSyncAccountAndIdIndex ON Events ("
                + Events._SYNC_ACCOUNT_TYPE + ", " + Events._SYNC_ACCOUNT + ", "
                + Events._SYNC_ID + ");");

        db.execSQL("CREATE INDEX eventsCalendarIdIndex ON Events (" +
                Events.CALENDAR_ID +
                ");");

        db.execSQL("CREATE TABLE EventsRawTimes (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER NOT NULL," +
                "dtstart2445 TEXT," +
                "dtend2445 TEXT," +
                "originalInstanceTime2445 TEXT," +
                "lastDate2445 TEXT," +
                "UNIQUE (event_id)" +
                ");");

        // NOTE: we do not create a trigger to delete an event's instances upon update,
        // as all rows currently get updated during a merge.

        db.execSQL("CREATE TABLE DeletedEvents (" +
                "_sync_id TEXT," +
                "_sync_version TEXT," +
                "_sync_account TEXT," +
                "_sync_account_type TEXT," +
                (isTemporary() ? "_sync_local_id INTEGER," : "") + // Used while syncing,
                "_sync_mark INTEGER," + // To filter out new rows
                "calendar_id INTEGER" +
                ");");

        db.execSQL("CREATE TABLE Instances (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER," +
                "begin INTEGER," +         // UTC millis
                "end INTEGER," +           // UTC millis
                "startDay INTEGER," +      // Julian start day
                "endDay INTEGER," +        // Julian end day
                "startMinute INTEGER," +   // minutes from midnight
                "endMinute INTEGER," +     // minutes from midnight
                "UNIQUE (event_id, begin, end)" +
                ");");

        db.execSQL("CREATE INDEX instancesStartDayIndex ON Instances (" +
                Instances.START_DAY +
                ");");

        db.execSQL("CREATE TABLE CalendarMetaData (" +
                "_id INTEGER PRIMARY KEY," +
                "localTimezone TEXT," +
                "minInstance INTEGER," +      // UTC millis
                "maxInstance INTEGER" +      // UTC millis
                ");");

        db.execSQL("CREATE TABLE Attendees (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER," +
                "attendeeName TEXT," +
                "attendeeEmail TEXT," +
                "attendeeStatus INTEGER," +
                "attendeeRelationship INTEGER," +
                "attendeeType INTEGER" +
                ");");

        db.execSQL("CREATE INDEX attendeesEventIdIndex ON Attendees (" +
                Attendees.EVENT_ID +
                ");");

        db.execSQL("CREATE TABLE Reminders (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER," +
                "minutes INTEGER," +
                "method INTEGER NOT NULL" +
                " DEFAULT " + Reminders.METHOD_DEFAULT +
                ");");

        db.execSQL("CREATE INDEX remindersEventIdIndex ON Reminders (" +
                Reminders.EVENT_ID +
                ");");

        // This table stores the Calendar notifications that have gone off.
        db.execSQL("CREATE TABLE CalendarAlerts (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER," +
                "begin INTEGER NOT NULL," +         // Instance begin - UTC millis
                "end INTEGER NOT NULL," +           // Instance end - UTC millis
                "alarmTime INTEGER NOT NULL," +     // UTC millis
                "creationTime INTEGER NOT NULL," +  // UTC millis
                "receivedTime INTEGER NOT NULL," +  // UTC millis
                "notifyTime INTEGER NOT NULL," +    // UTC millis
                "state INTEGER NOT NULL," +
                "minutes INTEGER," +                // Reminder - minutes until begin
                "UNIQUE (alarmTime, begin, event_id)" +
                ");");

        db.execSQL("CREATE INDEX calendarAlertsEventIdIndex ON CalendarAlerts (" +
                CalendarAlerts.EVENT_ID +
                ");");

        db.execSQL("CREATE TABLE ExtendedProperties (" +
                "_id INTEGER PRIMARY KEY," +
                "event_id INTEGER," +
                "name TEXT," +
                "value TEXT" +
                ");");

        db.execSQL("CREATE INDEX extendedPropertiesEventIdIndex ON ExtendedProperties (" +
                ExtendedProperties.EVENT_ID +
                ");");

        // Trigger to remove data tied to an event when we delete that event.
        db.execSQL("CREATE TRIGGER events_cleanup_delete DELETE ON Events " +
                "BEGIN " +
                "DELETE FROM Instances WHERE event_id = old._id;" +
                "DELETE FROM EventsRawTimes WHERE event_id = old._id;" +
                "DELETE FROM Attendees WHERE event_id = old._id;" +
                "DELETE FROM Reminders WHERE event_id = old._id;" +
                "DELETE FROM ExtendedProperties WHERE event_id = old._id;" +
                "END");

        // Triggers to set the _sync_dirty flag when an attendee is changed,
        // inserted or deleted
        db.execSQL("CREATE TRIGGER attendees_update UPDATE ON Attendees " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER attendees_insert INSERT ON Attendees " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=new.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER attendees_delete DELETE ON Attendees " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");

        // Triggers to set the _sync_dirty flag when a reminder is changed,
        // inserted or deleted
        db.execSQL("CREATE TRIGGER reminders_update UPDATE ON Reminders " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER reminders_insert INSERT ON Reminders " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=new.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER reminders_delete DELETE ON Reminders " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");
        // Triggers to set the _sync_dirty flag when an extended property is changed,
        // inserted or deleted
        db.execSQL("CREATE TRIGGER extended_properties_update UPDATE ON ExtendedProperties " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER extended_properties_insert INSERT ON ExtendedProperties " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=new.event_id;" +
                "END");
        db.execSQL("CREATE TRIGGER extended_properties_delete DELETE ON ExtendedProperties " +
                "BEGIN " +
                "UPDATE Events SET _sync_dirty=1 WHERE Events._id=old.event_id;" +
                "END");
    }

    /**
     * Make sure that there are no entries for accounts that no longer
     * exist. We are overriding this since we need to delete from the
     * Calendars table, which is not syncable, which has triggers that
     * will delete from the Events and DeletedEvents tables, which are
     * syncable.
     */
    @Override
    protected void onAccountsChanged(final Account[] accountsArray) {
        super.onAccountsChanged(accountsArray);

        final Map<Account, Boolean> accounts = Maps.newHashMap();
        for (Account account : accountsArray) {
            accounts.put(account, false);
        }

        mDb.beginTransaction();
        try {
            deleteRowsForRemovedAccounts(accounts, "Calendars");
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }

        if (mCalendarClient == null) {
            return;
        }

        // If we have calendars for unknown accounts, delete them.
        // If there are no calendars at all for a given account, add the
        // default calendar.

        // TODO: allow caller to specify which account's feeds should be updated
        String[] features = new String[]{
                GoogleLoginServiceConstants.FEATURE_LEGACY_HOSTED_OR_GOOGLE};
        AccountManagerCallback<Account[]> callback = new AccountManagerCallback<Account[]>() {
            public void run(AccountManagerFuture<Account[]> accountManagerFuture) {
                Account[] currentAccounts = new Account[0];
                try {
                    currentAccounts = accountManagerFuture.getResult();
                } catch (OperationCanceledException e) {
                    Log.w(TAG, "onAccountsChanged", e);
                    return;
                } catch (IOException e) {
                    Log.w(TAG, "onAccountsChanged", e);
                    return;
                } catch (AuthenticatorException e) {
                    Log.w(TAG, "onAccountsChanged", e);
                    return;
                }
                if (currentAccounts.length < 1) {
                    Log.w(TAG, "getPrimaryAccount: no primary account configured.");
                    return;
                }
                Account primaryAccount = currentAccounts[0];

                for (Map.Entry<Account, Boolean> entry : accounts.entrySet()) {
                    // TODO: change this when Calendar supports multiple accounts. Until then
                    // pretend that only the primary exists.
                    boolean ignore = primaryAccount == null ||
                            !primaryAccount.equals(entry.getKey());
                    entry.setValue(ignore);
                }

                Set<Account> handledAccounts = Sets.newHashSet();
                if (Config.LOGV) Log.v(TAG, "querying calendars");
                Cursor c = queryInternal(Calendars.CONTENT_URI, ACCOUNTS_PROJECTION, null, null,
                        null);
                try {
                    while (c.moveToNext()) {
                        final String accountName = c.getString(0);
                        final String accountType = c.getString(1);
                        final Account account = new Account(accountName, accountType);
                        if (handledAccounts.contains(account)) {
                            continue;
                        }
                        handledAccounts.add(account);
                        if (accounts.containsKey(account)) {
                            if (Config.LOGV) {
                                Log.v(TAG, "calendars for account " + account + " exist");
                            }
                            accounts.put(account, true /* hasCalendar */);
                        }
                    }
                } finally {
                    c.close();
                    c = null;
                }

                if (Config.LOGV) {
                    Log.v(TAG, "scanning over " + accounts.size() + " account(s)");
                }
                for (Map.Entry<Account, Boolean> entry : accounts.entrySet()) {
                    final Account account = entry.getKey();
                    boolean hasCalendar = entry.getValue();
                    if (hasCalendar) {
                        if (Config.LOGV) {
                            Log.v(TAG, "ignoring account " + account +
                                    " since it matched an existing calendar");
                        }
                        continue;
                    }
                    String feedUrl = mCalendarClient.getDefaultCalendarUrl(account.name,
                            CalendarClient.PROJECTION_PRIVATE_FULL, null/* query params */);
                    feedUrl = CalendarSyncAdapter.rewriteUrlforAccount(account, feedUrl);
                    if (Config.LOGV) {
                        Log.v(TAG, "adding default calendar for account " + account);
                    }
                    ContentValues values = new ContentValues();
                    values.put(Calendars._SYNC_ACCOUNT, account.name);
                    values.put(Calendars._SYNC_ACCOUNT_TYPE, account.type);
                    values.put(Calendars.URL, feedUrl);
                    values.put(Calendars.OWNER_ACCOUNT,
                            CalendarSyncAdapter.calendarEmailAddressFromFeedUrl(feedUrl));
                    values.put(Calendars.DISPLAY_NAME,
                            getContext().getString(R.string.calendar_default_name));
                    values.put(Calendars.SYNC_EVENTS, 1);
                    values.put(Calendars.SELECTED, 1);
                    values.put(Calendars.HIDDEN, 0);
                    values.put(Calendars.COLOR, -14069085 /* blue */);
                    // this is just our best guess.  the real value will get updated
                    // when the user does a sync.
                    values.put(Calendars.TIMEZONE, Time.getCurrentTimezone());
                    values.put(Calendars.ACCESS_LEVEL, Calendars.OWNER_ACCESS);
                    insertInternal(Calendars.CONTENT_URI, values);

                    scheduleSync(account, false /* do a full sync */, null /* no url */);

                }
                // Call the CalendarSyncAdapter's onAccountsChanged
                getTempProviderSyncAdapter().onAccountsChanged(accountsArray);
            }
        };

        AccountManager.get(getContext()).getAccountsByTypeAndFeatures(
                GoogleLoginServiceConstants.ACCOUNT_TYPE, features, callback, null);
    }

    @Override
    public Cursor queryInternal(Uri url, String[] projectionIn,
            String selection, String[] selectionArgs, String sort) {
        final SQLiteDatabase db = getDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        Cursor ret;

        // Generate the body of the query
        int match = sURLMatcher.match(url);
        switch (match)
        {
            case EVENTS:
                qb.setTables("Events, Calendars");
                qb.setProjectionMap(sEventsProjectionMap);
                qb.appendWhere("Events.calendar_id=Calendars._id");
                break;
            case EVENTS_ID:
                qb.setTables("Events, Calendars");
                qb.setProjectionMap(sEventsProjectionMap);
                qb.appendWhere("Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            case DELETED_EVENTS:
                if (isTemporary()) {
                    qb.setTables("DeletedEvents");
                    break;
                } else {
                    throw new IllegalArgumentException("Unknown URL " + url);
                }
            case CALENDARS:
                qb.setTables("Calendars");
                break;
            case CALENDARS_ID:
                qb.setTables("Calendars");
                qb.appendWhere("_id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            case INSTANCES:
            case INSTANCES_BY_DAY:
                long begin;
                long end;
                try {
                    begin = Long.valueOf(url.getPathSegments().get(2));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Cannot parse begin "
                            + url.getPathSegments().get(2));
                }
                try {
                    end = Long.valueOf(url.getPathSegments().get(3));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Cannot parse end "
                            + url.getPathSegments().get(3));
                }
                return handleInstanceQuery(qb, begin, end, projectionIn,
                        selection, sort, match == INSTANCES_BY_DAY);
            case EVENT_DAYS:
                int startDay;
                int endDay;
                try {
                    startDay = Integer.valueOf(url.getPathSegments().get(2));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Cannot parse start day "
                            + url.getPathSegments().get(2));
                }
                try {
                    endDay = Integer.valueOf(url.getPathSegments().get(3));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Cannot parse end day "
                            + url.getPathSegments().get(3));
                }
                return handleEventDayQuery(qb, startDay, endDay, projectionIn, selection);
            case ATTENDEES:
                qb.setTables("Attendees, Events, Calendars");
                qb.setProjectionMap(sAttendeesProjectionMap);
                qb.appendWhere("Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=Attendees.event_id");
                break;
            case ATTENDEES_ID:
                qb.setTables("Attendees, Events, Calendars");
                qb.setProjectionMap(sAttendeesProjectionMap);
                qb.appendWhere("Attendees._id=");
                qb.appendWhere(url.getPathSegments().get(1));
                qb.appendWhere(" AND Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=Attendees.event_id");
                break;
            case REMINDERS:
                qb.setTables("Reminders");
                break;
            case REMINDERS_ID:
                qb.setTables("Reminders, Events, Calendars");
                qb.setProjectionMap(sRemindersProjectionMap);
                qb.appendWhere("Reminders._id=");
                qb.appendWhere(url.getLastPathSegment());
                qb.appendWhere(" AND Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=Reminders.event_id");
                break;
            case CALENDAR_ALERTS:
                qb.setTables("CalendarAlerts, Events, Calendars");
                qb.setProjectionMap(sCalendarAlertsProjectionMap);
                qb.appendWhere("Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=CalendarAlerts.event_id");
                break;
            case CALENDAR_ALERTS_BY_INSTANCE:
                qb.setTables("CalendarAlerts, Events, Calendars");
                qb.setProjectionMap(sCalendarAlertsProjectionMap);
                qb.appendWhere("Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=CalendarAlerts.event_id");
                String groupBy = CalendarAlerts.EVENT_ID + "," + CalendarAlerts.BEGIN;
                return qb.query(db, projectionIn, selection, selectionArgs,
                        groupBy, null, sort);
            case CALENDAR_ALERTS_ID:
                qb.setTables("CalendarAlerts, Events, Calendars");
                qb.setProjectionMap(sCalendarAlertsProjectionMap);
                qb.appendWhere("CalendarAlerts._id=");
                qb.appendWhere(url.getLastPathSegment());
                qb.appendWhere(" AND Events.calendar_id=Calendars._id");
                qb.appendWhere(" AND Events._id=CalendarAlerts.event_id");
                break;
            case EXTENDED_PROPERTIES:
                qb.setTables("ExtendedProperties");
                break;
            case EXTENDED_PROPERTIES_ID:
                qb.setTables("ExtendedProperties, Events, Calendars");
                // not sure if we need a projection map or a join.  see what callers want.
//                qb.setProjectionMap(sExtendedPropertiesProjectionMap);
                qb.appendWhere("ExtendedProperties._id=");
                qb.appendWhere(url.getPathSegments().get(1));
//                qb.appendWhere(" AND Events.calendar_id = Calendars._id");
//                qb.appendWhere(" AND Events._id=ExtendedProperties.event_id");
                break;

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }

        // run the query
        ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);

        return ret;
    }

    /*
     * Fills the Instances table, if necessary, for the given range and then
     * queries the Instances table.
     *
     * @param qb The query
     * @param rangeBegin start of range (Julian days or ms)
     * @param rangeEnd end of range (Julian days or ms)
     * @param projectionIn The projection
     * @param selection The selection
     * @param sort How to sort
     * @param searchByDay if true, range is in Julian days, if false, range is in ms
     * @return
     */
    private Cursor handleInstanceQuery(SQLiteQueryBuilder qb, long rangeBegin,
            long rangeEnd, String[] projectionIn,
            String selection, String sort, boolean searchByDay) {
        final SQLiteDatabase db = getDatabase();

        qb.setTables("Instances INNER JOIN Events ON (Instances.event_id=Events._id) " +
                "INNER JOIN Calendars ON (Events.calendar_id = Calendars._id)");
        qb.setProjectionMap(sInstancesProjectionMap);
        if (searchByDay) {
            // Convert the first and last Julian day range to a range that uses
            // UTC milliseconds.
            Time time = new Time();
            long beginMs = time.setJulianDay((int) rangeBegin);
            // We add one to lastDay because the time is set to 12am on the given
            // Julian day and we want to include all the events on the last day.
            long endMs = time.setJulianDay((int) rangeEnd + 1);
            // will lock the database.
            acquireInstanceRange(beginMs, endMs, true /* use minimum expansion window */);
            qb.appendWhere("startDay <= ");
            qb.appendWhere(String.valueOf(rangeEnd));
            qb.appendWhere(" AND endDay >= ");
        } else {
            // will lock the database.
            acquireInstanceRange(rangeBegin, rangeEnd, true /* use minimum expansion window */);
            qb.appendWhere("begin <= ");
            qb.appendWhere(String.valueOf(rangeEnd));
            qb.appendWhere(" AND end >= ");
        }
        qb.appendWhere(String.valueOf(rangeBegin));
        return qb.query(db, projectionIn, selection, null, null, null, sort);
    }

    private Cursor handleEventDayQuery(SQLiteQueryBuilder qb, int begin, int end,
            String[] projection, String selection) {
        final SQLiteDatabase db = getDatabase();
        qb.setTables("Instances INNER JOIN Events ON (Instances.event_id=Events._id) " +
                "INNER JOIN Calendars ON (Events.calendar_id = Calendars._id)");
        qb.setProjectionMap(sInstancesProjectionMap);
        // Convert the first and last Julian day range to a range that uses
        // UTC milliseconds.
        Time time = new Time();
        long beginMs = time.setJulianDay((int) begin);
        // We add one to lastDay because the time is set to 12am on the given
        // Julian day and we want to include all the events on the last day.
        long endMs = time.setJulianDay((int) end + 1);

        acquireInstanceRange(beginMs, endMs, true);
        qb.appendWhere("startDay <= ");
        qb.appendWhere(String.valueOf(end));
        qb.appendWhere(" AND endDay >= ");
        qb.appendWhere(String.valueOf(begin));
        return qb.query(db, projection, selection, null /* selectionArgs */,
                Instances.START_DAY /* groupBy */, null /* having */, null);
    }

    /**
     * Ensure that the date range given has all elements in the instance
     * table.  Acquires the database lock and calls {@link #acquireInstanceRangeLocked}.
     *
     * @param begin start of range (ms)
     * @param end end of range (ms)
     * @param useMinimumExpansionWindow expand by at least MINIMUM_EXPANSION_SPAN
     */
    private void acquireInstanceRange(final long begin,
            final long end,
            final boolean useMinimumExpansionWindow) {
        mDb.beginTransaction();
        try {
            acquireInstanceRangeLocked(begin, end, useMinimumExpansionWindow);
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }
    }

    /**
     * Ensure that the date range given has all elements in the instance
     * table.  The database lock must be held when calling this method.
     *
     * @param begin start of range (ms)
     * @param end end of range (ms)
     * @param useMinimumExpansionWindow expand by at least MINIMUM_EXPANSION_SPAN
     */
    private void acquireInstanceRangeLocked(long begin, long end,
            boolean useMinimumExpansionWindow) {
        long expandBegin = begin;
        long expandEnd = end;

        if (useMinimumExpansionWindow) {
            // if we end up having to expand events into the instances table, expand
            // events for a minimal amount of time, so we do not have to perform
            // expansions frequently.
            long span = end - begin;
            if (span < MINIMUM_EXPANSION_SPAN) {
                long additionalRange = (MINIMUM_EXPANSION_SPAN - span) / 2;
                expandBegin -= additionalRange;
                expandEnd += additionalRange;
            }
        }

        // Check if the timezone has changed.
        // We do this check here because the database is locked and we can
        // safely delete all the entries in the Instances table.
        MetaData.Fields fields = mMetaData.getFieldsLocked();
        String dbTimezone = fields.timezone;
        long maxInstance = fields.maxInstance;
        long minInstance = fields.minInstance;
        String localTimezone = TimeZone.getDefault().getID();
        boolean timezoneChanged = (dbTimezone == null) || !dbTimezone.equals(localTimezone);

        if (maxInstance == 0 || timezoneChanged) {
            // Empty the Instances table and expand from scratch.
            mDb.execSQL("DELETE FROM Instances;");
            if (Config.LOGV) {
                Log.v(TAG, "acquireInstanceRangeLocked() deleted Instances,"
                        + " timezone changed: " + timezoneChanged);
            }
            expandInstanceRangeLocked(expandBegin, expandEnd, localTimezone);

            mMetaData.writeLocked(localTimezone, expandBegin, expandEnd);
            return;
        }

        // If the desired range [begin, end] has already been
        // expanded, then simply return.  The range is inclusive, that is,
        // events that touch either endpoint are included in the expansion.
        // This means that a zero-duration event that starts and ends at
        // the endpoint will be included.
        // We use [begin, end] here and not [expandBegin, expandEnd] for
        // checking the range because a common case is for the client to
        // request successive days or weeks, for example.  If we checked
        // that the expanded range [expandBegin, expandEnd] then we would
        // always be expanding because there would always be one more day
        // or week that hasn't been expanded.
        if ((begin >= minInstance) && (end <= maxInstance)) {
            if (Config.LOGV) {
                Log.v(TAG, "Canceled instance query (" + expandBegin + ", " + expandEnd
                        + ") falls within previously expanded range.");
            }
            return;
        }

        // If the requested begin point has not been expanded, then include
        // more events than requested in the expansion (use "expandBegin").
        if (begin < minInstance) {
            expandInstanceRangeLocked(expandBegin, minInstance, localTimezone);
            minInstance = expandBegin;
        }

        // If the requested end point has not been expanded, then include
        // more events than requested in the expansion (use "expandEnd").
        if (end > maxInstance) {
            expandInstanceRangeLocked(maxInstance, expandEnd, localTimezone);
            maxInstance = expandEnd;
        }

        // Update the bounds on the Instances table.
        mMetaData.writeLocked(localTimezone, minInstance, maxInstance);
    }

    private static final String[] EXPAND_COLUMNS = new String[] {
            Events._ID,
            Events._SYNC_ID,
            Events.STATUS,
            Events.DTSTART,
            Events.DTEND,
            Events.EVENT_TIMEZONE,
            Events.RRULE,
            Events.RDATE,
            Events.EXRULE,
            Events.EXDATE,
            Events.DURATION,
            Events.ALL_DAY,
            Events.ORIGINAL_EVENT,
            Events.ORIGINAL_INSTANCE_TIME
    };

    /**
     * Make instances for the given range.
     */
    private void expandInstanceRangeLocked(long begin, long end, String localTimezone) {

        if (PROFILE) {
            Debug.startMethodTracing("expandInstanceRangeLocked");
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Expanding events between " + begin + " and " + end);
        }

        Cursor entries = getEntries(begin, end);
        try {
            performInstanceExpansion(begin, end, localTimezone, entries);
        } finally {
            if (entries != null) {
                entries.close();
            }
        }
        if (PROFILE) {
            Debug.stopMethodTracing();
        }
    }

    /**
     * Get all entries affecting the given window.
     * @param begin Window start (ms).
     * @param end Window end (ms).
     * @return Cursor for the entries; caller must close it.
     */
    private Cursor getEntries(long begin, long end) {
        final SQLiteDatabase db = getDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("Events INNER JOIN Calendars ON (calendar_id = Calendars._id)");
        qb.setProjectionMap(sEventsProjectionMap);

        String beginString = String.valueOf(begin);
        String endString = String.valueOf(end);

        qb.appendWhere("(dtstart <= ");
        qb.appendWhere(endString);
        qb.appendWhere(" AND ");
        qb.appendWhere("(lastDate IS NULL OR lastDate >= ");
        qb.appendWhere(beginString);
        qb.appendWhere(")) OR (");
        // grab recurrence exceptions that fall outside our expansion window but modify
        // recurrences that do fall within our window.  we won't insert these into the output
        // set of instances, but instead will just add them to our cancellations list, so we
        // can cancel the correct recurrence expansion instances.
        qb.appendWhere("originalInstanceTime IS NOT NULL ");
        qb.appendWhere("AND originalInstanceTime <= ");
        qb.appendWhere(endString);
        qb.appendWhere(" AND ");
        // we don't have originalInstanceDuration or end time.  for now, assume the original
        // instance lasts no longer than 1 week.
        // TODO: compute the originalInstanceEndTime or get this from the server.
        qb.appendWhere("originalInstanceTime >= ");
        qb.appendWhere(String.valueOf(begin - MAX_ASSUMED_DURATION));
        qb.appendWhere(")");

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Retrieving events to expand: " + qb.toString());
        }

        return qb.query(db, EXPAND_COLUMNS, null, null, null, null, null);
    }

    /**
     * Perform instance expansion on the given entries.
     * @param begin Window start (ms).
     * @param end Window end (ms).
     * @param localTimezone
     * @param entries The entries to process.
     */
    private void performInstanceExpansion(long begin, long end, String localTimezone, Cursor entries) {
        RecurrenceProcessor rp = new RecurrenceProcessor();

        int statusColumn = entries.getColumnIndex(Events.STATUS);
        int dtstartColumn = entries.getColumnIndex(Events.DTSTART);
        int dtendColumn = entries.getColumnIndex(Events.DTEND);
        int eventTimezoneColumn = entries.getColumnIndex(Events.EVENT_TIMEZONE);
        int durationColumn = entries.getColumnIndex(Events.DURATION);
        int rruleColumn = entries.getColumnIndex(Events.RRULE);
        int rdateColumn = entries.getColumnIndex(Events.RDATE);
        int exruleColumn = entries.getColumnIndex(Events.EXRULE);
        int exdateColumn = entries.getColumnIndex(Events.EXDATE);
        int allDayColumn = entries.getColumnIndex(Events.ALL_DAY);
        int idColumn = entries.getColumnIndex(Events._ID);
        int syncIdColumn = entries.getColumnIndex(Events._SYNC_ID);
        int originalEventColumn = entries.getColumnIndex(Events.ORIGINAL_EVENT);
        int originalInstanceTimeColumn = entries.getColumnIndex(Events.ORIGINAL_INSTANCE_TIME);

        ContentValues initialValues;
        EventInstancesMap instancesMap = new EventInstancesMap();

        Duration duration = new Duration();
        Time eventTime = new Time();

        // Invariant: entries contains all events that affect the current
        // window.  It consists of:
        // a) Individual events that fall in the window.  These will be
        //    displayed.
        // b) Recurrences that included the window.  These will be displayed
        //    if not canceled.
        // c) Recurrence exceptions that fall in the window.  These will be
        //    displayed if not cancellations.
        // d) Recurrence exceptions that modify an instance inside the
        //    window (subject to 1 week assumption above), but are outside
        //    the window.  These will not be displayed.  Cases c and d are
        //    distingushed by the start / end time.

        while (entries.moveToNext()) {
            try {
                initialValues = null;

                boolean allDay = entries.getInt(allDayColumn) != 0;

                String eventTimezone = entries.getString(eventTimezoneColumn);
                if (allDay || TextUtils.isEmpty(eventTimezone)) {
                    // in the events table, allDay events start at midnight.
                    // this forces them to stay at midnight for all day events
                    // TODO: check that this actually does the right thing.
                    eventTimezone = Time.TIMEZONE_UTC;
                }

                long dtstartMillis = entries.getLong(dtstartColumn);
                Long eventId = Long.valueOf(entries.getLong(idColumn));

                String durationStr = entries.getString(durationColumn);
                if (durationStr != null) {
                    try {
                        duration.parse(durationStr);
                    }
                    catch (DateException e) {
                        Log.w(TAG, "error parsing duration for event "
                                + eventId + "'" + durationStr + "'", e);
                        duration.sign = 1;
                        duration.weeks = 0;
                        duration.days = 0;
                        duration.hours = 0;
                        duration.minutes = 0;
                        duration.seconds = 0;
                        durationStr = "+P0S";
                    }
                }

                String syncId = entries.getString(syncIdColumn);
                String originalEvent = entries.getString(originalEventColumn);

                long originalInstanceTimeMillis = -1;
                if (!entries.isNull(originalInstanceTimeColumn)) {
                    originalInstanceTimeMillis= entries.getLong(originalInstanceTimeColumn);
                }
                int status = entries.getInt(statusColumn);

                String rruleStr = entries.getString(rruleColumn);
                String rdateStr = entries.getString(rdateColumn);
                String exruleStr = entries.getString(exruleColumn);
                String exdateStr = entries.getString(exdateColumn);

                RecurrenceSet recur = new RecurrenceSet(rruleStr, rdateStr, exruleStr, exdateStr);

                if (recur.hasRecurrence()) {
                    // the event is repeating

                    if (status == Events.STATUS_CANCELED) {
                        // should not happen!
                        Log.e(TAG, "Found canceled recurring event in "
                                + "Events table.  Ignoring.");
                        continue;
                    }

                    // need to parse the event into a local calendar.
                    eventTime.timezone = eventTimezone;
                    eventTime.set(dtstartMillis);
                    eventTime.allDay = allDay;

                    if (durationStr == null) {
                        // should not happen.
                        Log.e(TAG, "Repeating event has no duration -- "
                                + "should not happen.");
                        if (allDay) {
                            // set to one day.
                            duration.sign = 1;
                            duration.weeks = 0;
                            duration.days = 1;
                            duration.hours = 0;
                            duration.minutes = 0;
                            duration.seconds = 0;
                            durationStr = "+P1D";
                        } else {
                            // compute the duration from dtend, if we can.
                            // otherwise, use 0s.
                            duration.sign = 1;
                            duration.weeks = 0;
                            duration.days = 0;
                            duration.hours = 0;
                            duration.minutes = 0;
                            if (!entries.isNull(dtendColumn)) {
                                long dtendMillis = entries.getLong(dtendColumn);
                                duration.seconds = (int) ((dtendMillis - dtstartMillis) / 1000);
                                durationStr = "+P" + duration.seconds + "S";
                            } else {
                                duration.seconds = 0;
                                durationStr = "+P0S";
                            }
                        }
                    }

                    long[] dates;
                    dates = rp.expand(eventTime, recur, begin, end);

                    // Initialize the "eventTime" timezone outside the loop.
                    // This is used in computeTimezoneDependentFields().
                    if (allDay) {
                        eventTime.timezone = Time.TIMEZONE_UTC;
                    } else {
                        eventTime.timezone = localTimezone;
                    }

                    long durationMillis = duration.getMillis();
                    for (long date : dates) {
                        initialValues = new ContentValues();
                        initialValues.put(Instances.EVENT_ID, eventId);

                        initialValues.put(Instances.BEGIN, date);
                        long dtendMillis = date + durationMillis;
                        initialValues.put(Instances.END, dtendMillis);

                        computeTimezoneDependentFields(date, dtendMillis,
                                eventTime, initialValues);
                        instancesMap.add(syncId, initialValues);
                    }
                } else {
                    // the event is not repeating
                    initialValues = new ContentValues();

                    // if this event has an "original" field, then record
                    // that we need to cancel the original event (we can't
                    // do that here because the order of this loop isn't
                    // defined)
                    if (originalEvent != null && originalInstanceTimeMillis != -1) {
                        initialValues.put(Events.ORIGINAL_EVENT, originalEvent);
                        initialValues.put(Events.ORIGINAL_INSTANCE_TIME,
                                originalInstanceTimeMillis);
                        initialValues.put(Events.STATUS, status);
                    }

                    long dtendMillis = dtstartMillis;
                    if (durationStr == null) {
                        if (!entries.isNull(dtendColumn)) {
                            dtendMillis = entries.getLong(dtendColumn);
                        }
                    } else {
                        dtendMillis = duration.addTo(dtstartMillis);
                    }

                    // this non-recurring event might be a recurrence exception that doesn't
                    // actually fall within our expansion window, but instead was selected
                    // so we can correctly cancel expanded recurrence instances below.  do not
                    // add events to the instances map if they don't actually fall within our
                    // expansion window.
                    if ((dtendMillis < begin) || (dtstartMillis > end)) {
                        if (originalEvent != null && originalInstanceTimeMillis != -1) {
                            initialValues.put(Events.STATUS, Events.STATUS_CANCELED);
                        } else {
                            Log.w(TAG, "Unexpected event outside window: " + syncId);
                            continue;
                        }
                    }

                    initialValues.put(Instances.EVENT_ID, eventId);
                    initialValues.put(Instances.BEGIN, dtstartMillis);

                    initialValues.put(Instances.END, dtendMillis);

                    if (allDay) {
                        eventTime.timezone = Time.TIMEZONE_UTC;
                    } else {
                        eventTime.timezone = localTimezone;
                    }
                    computeTimezoneDependentFields(dtstartMillis, dtendMillis,
                            eventTime, initialValues);

                    instancesMap.add(syncId, initialValues);
                }
            } catch (DateException e) {
                Log.w(TAG, "RecurrenceProcessor error ", e);
            } catch (TimeFormatException e) {
                Log.w(TAG, "RecurrenceProcessor error ", e);
            }
        }

        // Invariant: instancesMap contains all instances that affect the
        // window, indexed by original sync id.  It consists of:
        // a) Individual events that fall in the window.  They have:
        //   EVENT_ID, BEGIN, END
        // b) Instances of recurrences that fall in the window.  They may
        //   be subject to exceptions.  They have:
        //   EVENT_ID, BEGIN, END
        // c) Exceptions that fall in the window.  They have:
        //   ORIGINAL_EVENT, ORIGINAL_INSTANCE_TIME, STATUS (since they can
        //   be a modification or cancellation), EVENT_ID, BEGIN, END
        // d) Recurrence exceptions that modify an instance inside the
        //   window but fall outside the window.  They have:
        //   ORIGINAL_EVENT, ORIGINAL_INSTANCE_TIME, STATUS =
        //   STATUS_CANCELED, EVENT_ID, BEGIN, END

        // First, delete the original instances corresponding to recurrence
        // exceptions.  We do this by iterating over the list and for each
        // recurrence exception, we search the list for an instance with a
        // matching "original instance time".  If we find such an instance,
        // we remove it from the list.  If we don't find such an instance
        // then we cancel the recurrence exception.
        Set<String> keys = instancesMap.keySet();
        for (String syncId : keys) {
            InstancesList list = instancesMap.get(syncId);
            for (ContentValues values : list) {

                // If this instance is not a recurrence exception, then
                // skip it.
                if (!values.containsKey(Events.ORIGINAL_EVENT)) {
                    continue;
                }

                String originalEvent = values.getAsString(Events.ORIGINAL_EVENT);
                long originalTime = values.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
                InstancesList originalList = instancesMap.get(originalEvent);
                if (originalList == null) {
                    // The original recurrence is not present, so don't try canceling it.
                    continue;
                }

                // Search the original event for a matching original
                // instance time.  If there is a matching one, then remove
                // the original one.  We do this both for exceptions that
                // change the original instance as well as for exceptions
                // that delete the original instance.
                for (int num = originalList.size() - 1; num >= 0; num--) {
                    ContentValues originalValues = originalList.get(num);
                    long beginTime = originalValues.getAsLong(Instances.BEGIN);
                    if (beginTime == originalTime) {
                        // We found the original instance, so remove it.
                        originalList.remove(num);
                    }
                }
            }
        }

        // Invariant: instancesMap contains filtered instances.
        // It consists of:
        // a) Individual events that fall in the window.
        // b) Instances of recurrences that fall in the window and have not
        //   been subject to exceptions.
        // c) Exceptions that fall in the window.  They will have
        //   STATUS_CANCELED if they are cancellations.
        // d) Recurrence exceptions that modify an instance inside the
        //   window but fall outside the window.  These are STATUS_CANCELED.

        // Now do the inserts.  Since the db lock is held when this method is executed,
        // this will be done in a transaction.
        // NOTE: if there is lock contention (e.g., a sync is trying to merge into the db
        // while the calendar app is trying to query the db (expanding instances)), we will
        // not be "polite" and yield the lock until we're done.  This will favor local query
        // operations over sync/write operations.
        for (String syncId : keys) {
            InstancesList list = instancesMap.get(syncId);
            for (ContentValues values : list) {

                // If this instance was cancelled then don't create a new
                // instance.
                Integer status = values.getAsInteger(Events.STATUS);
                if (status != null && status == Events.STATUS_CANCELED) {
                    continue;
                }

                // Remove these fields before inserting a new instance
                values.remove(Events.ORIGINAL_EVENT);
                values.remove(Events.ORIGINAL_INSTANCE_TIME);
                values.remove(Events.STATUS);

                mInstancesInserter.replace(values);
            }
        }
    }

    /**
     * Computes the timezone-dependent fields of an instance of an event and
     * updates the "values" map to contain those fields.
     *
     * @param begin the start time of the instance (in UTC milliseconds)
     * @param end the end time of the instance (in UTC milliseconds)
     * @param local a Time object with the timezone set to the local timezone
     * @param values a map that will contain the timezone-dependent fields
     */
    private void computeTimezoneDependentFields(long begin, long end,
            Time local, ContentValues values) {
        local.set(begin);
        int startDay = Time.getJulianDay(begin, local.gmtoff);
        int startMinute = local.hour * 60 + local.minute;

        local.set(end);
        int endDay = Time.getJulianDay(end, local.gmtoff);
        int endMinute = local.hour * 60 + local.minute;

        // Special case for midnight, which has endMinute == 0.  Change
        // that to +24 hours on the previous day to make everything simpler.
        // Exception: if start and end minute are both 0 on the same day,
        // then leave endMinute alone.
        if (endMinute == 0 && endDay > startDay) {
            endMinute = 24 * 60;
            endDay -= 1;
        }

        values.put(Instances.START_DAY, startDay);
        values.put(Instances.END_DAY, endDay);
        values.put(Instances.START_MINUTE, startMinute);
        values.put(Instances.END_MINUTE, endMinute);
    }

    // Read the start and end time for an event from the Events table.
    // Also read the "all-day" indicator.
    private TimeRange readEventStartEnd(long eventId) {
        Cursor cursor = null;
        TimeRange range = new TimeRange();
        try {
            cursor = query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                    new String[] { Events.DTSTART, Events.DTEND, Events.ALL_DAY },
                    null /* selection */,
                    null /* selectionArgs */,
                    null /* sort */);
            if (cursor == null || !cursor.moveToFirst()) {
                Log.d(TAG, "Couldn't find " + eventId + " in Events table");
                return null;
            }
            range.begin = cursor.getLong(0);
            range.end = cursor.getLong(1);
            range.allDay = cursor.getInt(2) != 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return range;
    }

    @Override
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case EVENTS:
                return "vnd.android.cursor.dir/event";
            case EVENTS_ID:
                return "vnd.android.cursor.item/event";
            case REMINDERS:
                return "vnd.android.cursor.dir/reminder";
            case REMINDERS_ID:
                return "vnd.android.cursor.item/reminder";
            case CALENDAR_ALERTS:
                return "vnd.android.cursor.dir/calendar-alert";
            case CALENDAR_ALERTS_BY_INSTANCE:
                return "vnd.android.cursor.dir/calendar-alert-by-instance";
            case CALENDAR_ALERTS_ID:
                return "vnd.android.cursor.item/calendar-alert";
            case INSTANCES:
            case INSTANCES_BY_DAY:
            case EVENT_DAYS:
                return "vnd.android.cursor.dir/event-instance";
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    public static boolean isRecurrenceEvent(ContentValues values) {
        return (!TextUtils.isEmpty(values.getAsString(Events.RRULE))||
                !TextUtils.isEmpty(values.getAsString(Events.RDATE))||
                !TextUtils.isEmpty(values.getAsString(Events.ORIGINAL_EVENT)));
    }

    @Override
    public Uri insertInternal(Uri url, ContentValues initialValues) {
        final SQLiteDatabase db = getDatabase();
        long rowID;

        int match = sURLMatcher.match(url);
        switch (match) {
            case EVENTS:
                if (!isTemporary()) {
                    initialValues.put(Events._SYNC_DIRTY, 1);
                    if (!initialValues.containsKey(Events.DTSTART)) {
                        throw new RuntimeException("DTSTART field missing from event");
                    }
                }
                // TODO: avoid the call to updateBundleFromEvent if this is just finding local
                // changes.  or avoid for temp providers altogether, if we can compute this
                // during a merge.
                // TODO: do we really need to make a copy?
                ContentValues updatedValues = updateContentValuesFromEvent(initialValues);
                if (updatedValues == null) {
                    throw new RuntimeException("Could not insert event.");
                    // return null;
                }
                String owner = null;
                if (updatedValues.containsKey(Events.CALENDAR_ID) &&
                        !updatedValues.containsKey(Events.ORGANIZER)) {
                    owner = getOwner(updatedValues.getAsLong(Events.CALENDAR_ID));
                    // TODO: This isn't entirely correct.  If a guest is adding a recurrence
                    // exception to an event, the organizer should stay the original organizer.
                    // This value doesn't go to the server and it will get fixed on sync,
                    // so it shouldn't really matter.
                    if (owner != null) {
                        updatedValues.put(Events.ORGANIZER, owner);
                    }
                }

                long rowId = mEventsInserter.insert(updatedValues);
                Uri uri = Uri.parse("content://" + url.getAuthority() + "/events/" + rowId);
                if (!isTemporary() && rowId != -1) {
                    updateEventRawTimesLocked(rowId, updatedValues);
                    updateInstancesLocked(updatedValues, rowId, true /* new event */, db);

                    // If we inserted a new event that specified the self-attendee
                    // status, then we need to add an entry to the attendees table.
                    if (initialValues.containsKey(Events.SELF_ATTENDEE_STATUS)) {
                        int status = initialValues.getAsInteger(Events.SELF_ATTENDEE_STATUS);
                        if (owner == null) {
                            owner = getOwner(updatedValues.getAsLong(Events.CALENDAR_ID));
                        }
                        createAttendeeEntry(rowId, status, owner);
                    }
                    triggerAppWidgetUpdate(rowId);
                }

                return uri;
            case CALENDARS:
                if (!isTemporary()) {
                    Integer syncEvents = initialValues.getAsInteger(Calendars.SYNC_EVENTS);
                    if (syncEvents != null && syncEvents == 1) {
                        String accountName = initialValues.getAsString(Calendars._SYNC_ACCOUNT);
                        String accountType = initialValues.getAsString(
                                Calendars._SYNC_ACCOUNT_TYPE);
                        final Account account = new Account(accountName, accountType);
                        String calendarUrl = initialValues.getAsString(Calendars.URL);
                        scheduleSync(account, false /* two-way sync */, calendarUrl);
                    }
                }
                rowID = mCalendarsInserter.insert(initialValues);
                return ContentUris.withAppendedId(Calendars.CONTENT_URI, rowID);
            case ATTENDEES:
                if (!initialValues.containsKey(Attendees.EVENT_ID)) {
                    throw new IllegalArgumentException("Attendees values must "
                            + "contain an event_id");
                }
                rowID = mAttendeesInserter.insert(initialValues);

                // Copy the attendee status value to the Events table.
                updateEventAttendeeStatus(db, initialValues);

                return ContentUris.withAppendedId(Calendar.Attendees.CONTENT_URI, rowID);
            case REMINDERS:
                if (!initialValues.containsKey(Reminders.EVENT_ID)) {
                    throw new IllegalArgumentException("Reminders values must "
                            + "contain an event_id");
                }
                rowID = mRemindersInserter.insert(initialValues);

                if (!isTemporary()) {
                    // Schedule another event alarm, if necessary
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "insertInternal() changing reminder");
                    }
                    scheduleNextAlarm(false /* do not remove alarms */);
                }
                return ContentUris.withAppendedId(Calendar.Reminders.CONTENT_URI, rowID);
            case CALENDAR_ALERTS:
                if (!initialValues.containsKey(CalendarAlerts.EVENT_ID)) {
                    throw new IllegalArgumentException("CalendarAlerts values must "
                            + "contain an event_id");
                }
                rowID = mCalendarAlertsInserter.insert(initialValues);

                return Uri.parse(CalendarAlerts.CONTENT_URI + "/" + rowID);
            case EXTENDED_PROPERTIES:
                if (!initialValues.containsKey(Calendar.ExtendedProperties.EVENT_ID)) {
                    throw new IllegalArgumentException("ExtendedProperties values must "
                            + "contain an event_id");
                }
                rowID = mExtendedPropertiesInserter.insert(initialValues);

                return ContentUris.withAppendedId(ExtendedProperties.CONTENT_URI, rowID);
            case DELETED_EVENTS:
                if (isTemporary()) {
                    rowID = mDeletedEventsInserter.insert(initialValues);
                    return ContentUris.withAppendedId(Calendar.Events.DELETED_CONTENT_URI, rowID);
                }
                // fallthrough
            case EVENTS_ID:
            case REMINDERS_ID:
            case CALENDAR_ALERTS_ID:
            case EXTENDED_PROPERTIES_ID:
            case INSTANCES:
            case INSTANCES_BY_DAY:
            case EVENT_DAYS:
                throw new UnsupportedOperationException("Cannot insert into that URL");
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    /**
     * Gets the calendar's owner for an event.
     * @param calId
     * @return email of owner or null
     */
    private String getOwner(long calId) {
        // Get the email address of this user from this Calendar
        String emailAddress = null;
        Cursor cursor = null;
        try {
            cursor = query(ContentUris.withAppendedId(Calendars.CONTENT_URI, calId),
                    new String[] { Calendars.OWNER_ACCOUNT },
                    null /* selection */,
                    null /* selectionArgs */,
                    null /* sort */);
            if (cursor == null || !cursor.moveToFirst()) {
                Log.d(TAG, "Couldn't find " + calId + " in Calendars table");
                return null;
            }
            emailAddress = cursor.getString(0);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return emailAddress;
    }

    /**
     * Creates an entry in the Attendees table that refers to the given event
     * and that has the given response status.
     *
     * @param eventId the event id that the new entry in the Attendees table
     * should refer to
     * @param status the response status
     * @param emailAddress the email of the attendee
     */
    private void createAttendeeEntry(long eventId, int status, String emailAddress) {
        ContentValues values = new ContentValues();
        values.put(Attendees.EVENT_ID, eventId);
        values.put(Attendees.ATTENDEE_STATUS, status);
        values.put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_NONE);
        // TODO: The relationship could actually be ORGANIZER, but it will get straightened out
        // on sync.
        values.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ATTENDEE);
        values.put(Attendees.ATTENDEE_EMAIL, emailAddress);

        // We don't know the ATTENDEE_NAME but that will be filled in by the
        // server and sent back to us.
        mAttendeesInserter.insert(values);
    }

    /**
     * Updates the attendee status in the Events table to be consistent with
     * the value in the Attendees table.
     *
     * @param db the database
     * @param attendeeValues the column values for one row in the Attendees
     * table.
     */
    private void updateEventAttendeeStatus(SQLiteDatabase db, ContentValues attendeeValues) {
        // Get the event id for this attendee
        long eventId = attendeeValues.getAsLong(Attendees.EVENT_ID);

        if (MULTIPLE_ATTENDEES_PER_EVENT) {
            // Get the calendar id for this event
            Cursor cursor = null;
            long calId;
            try {
                cursor = query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                        new String[] { Events.CALENDAR_ID },
                        null /* selection */,
                        null /* selectionArgs */,
                        null /* sort */);
                if (cursor == null || !cursor.moveToFirst()) {
                    Log.d(TAG, "Couldn't find " + eventId + " in Events table");
                    return;
                }
                calId = cursor.getLong(0);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            // Get the owner email for this Calendar
            String calendarEmail = null;
            cursor = null;
            try {
                cursor = query(ContentUris.withAppendedId(Calendars.CONTENT_URI, calId),
                        new String[] { Calendars.OWNER_ACCOUNT },
                        null /* selection */,
                        null /* selectionArgs */,
                        null /* sort */);
                if (cursor == null || !cursor.moveToFirst()) {
                    Log.d(TAG, "Couldn't find " + calId + " in Calendars table");
                    return;
                }
                calendarEmail = cursor.getString(0);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            if (calendarEmail == null) {
                return;
            }

            // Get the email address for this attendee
            String attendeeEmail = null;
            if (attendeeValues.containsKey(Attendees.ATTENDEE_EMAIL)) {
                attendeeEmail = attendeeValues.getAsString(Attendees.ATTENDEE_EMAIL);
            }

            // If the attendee email does not match the calendar email, then this
            // attendee is not the owner of this calendar so we don't update the
            // selfAttendeeStatus in the event.
            if (!calendarEmail.equals(attendeeEmail)) {
                return;
            }
        }

        int status = Attendees.ATTENDEE_STATUS_NONE;
        if (attendeeValues.containsKey(Attendees.ATTENDEE_RELATIONSHIP)) {
            int rel = attendeeValues.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP);
            if (rel == Attendees.RELATIONSHIP_ORGANIZER) {
                status = Attendees.ATTENDEE_STATUS_ACCEPTED;
            }
        }

        if (attendeeValues.containsKey(Attendees.ATTENDEE_STATUS)) {
            status = attendeeValues.getAsInteger(Attendees.ATTENDEE_STATUS);
        }

        ContentValues values = new ContentValues();
        values.put(Events.SELF_ATTENDEE_STATUS, status);
        db.update("Events", values, "_id="+eventId, null);
    }

    /**
     * Updates the instances table when an event is added or updated.
     * @param values The new values of the event.
     * @param rowId The database row id of the event.
     * @param newEvent true if the event is new.
     * @param db The database
     */
    private void updateInstancesLocked(ContentValues values,
            long rowId,
            boolean newEvent,
            SQLiteDatabase db) {

        // If there are no expanded Instances, then return.
        MetaData.Fields fields = mMetaData.getFieldsLocked();
        if (fields.maxInstance == 0) {
            return;
        }

        Long dtstartMillis = values.getAsLong(Events.DTSTART);
        if (dtstartMillis == null) {
            if (newEvent) {
                // must be present for a new event.
                throw new RuntimeException("DTSTART missing.");
            }
            if (Config.LOGV) Log.v(TAG, "Missing DTSTART.  "
                    + "No need to update instance.");
            return;
        }

        Long lastDateMillis = values.getAsLong(Events.LAST_DATE);
        Long originalInstanceTime = values.getAsLong(Events.ORIGINAL_INSTANCE_TIME);

        if (!newEvent) {
            // Want to do this for regular event, recurrence, or exception.
            // For recurrence or exception, more deletion may happen below if we
            // do an instance expansion.  This deletion will suffice if the exception
            // is moved outside the window, for instance.
            db.delete("Instances", "event_id=" + rowId, null /* selectionArgs */);
        }

        if (isRecurrenceEvent(values))  {
            // The recurrence or exception needs to be (re-)expanded if:
            // a) Exception or recurrence that falls inside window
            boolean insideWindow = dtstartMillis <= fields.maxInstance &&
                    (lastDateMillis == null || lastDateMillis >= fields.minInstance);
            // b) Exception that affects instance inside window
            // These conditions match the query in getEntries
            //  See getEntries comment for explanation of subtracting 1 week.
            boolean affectsWindow = originalInstanceTime != null &&
                    originalInstanceTime <= fields.maxInstance &&
                    originalInstanceTime >= fields.minInstance - MAX_ASSUMED_DURATION;
            if (insideWindow || affectsWindow) {
                updateRecurrenceInstancesLocked(values, rowId, db);
            }
            // TODO: an exception creation or update could be optimized by
            // updating just the affected instances, instead of regenerating
            // the recurrence.
            return;
        }

        Long dtendMillis = values.getAsLong(Events.DTEND);
        if (dtendMillis == null) {
            dtendMillis = dtstartMillis;
        }

        // if the event is in the expanded range, insert
        // into the instances table.
        // TODO: deal with durations.  currently, durations are only used in
        // recurrences.

        if (dtstartMillis <= fields.maxInstance && dtendMillis >= fields.minInstance) {
            ContentValues instanceValues = new ContentValues();
            instanceValues.put(Instances.EVENT_ID, rowId);
            instanceValues.put(Instances.BEGIN, dtstartMillis);
            instanceValues.put(Instances.END, dtendMillis);

            boolean allDay = false;
            Integer allDayInteger = values.getAsInteger(Events.ALL_DAY);
            if (allDayInteger != null) {
                allDay = allDayInteger != 0;
            }

            // Update the timezone-dependent fields.
            Time local = new Time();
            if (allDay) {
                local.timezone = Time.TIMEZONE_UTC;
            } else {
                local.timezone = fields.timezone;
            }

            computeTimezoneDependentFields(dtstartMillis, dtendMillis, local, instanceValues);
            mInstancesInserter.insert(instanceValues);
        }
    }

    /**
     * Determines the recurrence entries associated with a particular recurrence.
     * This set is the base recurrence and any exception.
     *
     * Normally the entries are indicated by the sync id of the base recurrence
     * (which is the originalEvent in the exceptions).
     * However, a complication is that a recurrence may not yet have a sync id.
     * In that case, the recurrence is specified by the rowId.
     *
     * @param recurrenceSyncId The sync id of the base recurrence, or null.
     * @param rowId The row id of the base recurrence.
     * @return the relevant entries.
     */
    private Cursor getRelevantRecurrenceEntries(String recurrenceSyncId, long rowId) {
        final SQLiteDatabase db = getDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        qb.setTables("Events INNER JOIN Calendars ON (calendar_id = Calendars._id)");
        qb.setProjectionMap(sEventsProjectionMap);
        if (recurrenceSyncId == null) {
            String where = "Events._id = " + rowId;
            qb.appendWhere(where);
        } else {
            String where = "Events._sync_id = \"" + recurrenceSyncId + "\""
                    + " OR Events.originalEvent = \"" + recurrenceSyncId + "\"";
            qb.appendWhere(where);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Retrieving events to expand: " + qb.toString());
        }

        return qb.query(db, EXPAND_COLUMNS, null /* selection */, null /* selectionArgs */, null /* groupBy */, null /* having */, null /* sortOrder */);
    }

    /**
     * Do incremental Instances update of a recurrence or recurrence exception.
     *
     * This method does performInstanceExpansion on just the modified recurrence,
     * to avoid the overhead of recomputing the entire instance table.
     *
     * @param values The new values of the event.
     * @param rowId The database row id of the event.
     * @param db The database
     */
    private void updateRecurrenceInstancesLocked(ContentValues values,
            long rowId,
            SQLiteDatabase db) {
        MetaData.Fields fields = mMetaData.getFieldsLocked();
        String originalEvent = values.getAsString(Events.ORIGINAL_EVENT);
        String recurrenceSyncId = null;
        if (originalEvent != null) {
            recurrenceSyncId = originalEvent;
        } else {
            // Get the recurrence's sync id from the database
            recurrenceSyncId = DatabaseUtils.stringForQuery(db, "SELECT _sync_id FROM Events"
                    + " WHERE _id = " + rowId, null /* selection args */);
        }
        // recurrenceSyncId is the _sync_id of the underlying recurrence
        // If the recurrence hasn't gone to the server, it will be null.

        // Need to clear out old instances
        if (recurrenceSyncId == null) {
            // Creating updating a recurrence that hasn't gone to the server.
            // Need to delete based on row id
            String where = "_id IN (SELECT Instances._id as _id"
                    + " FROM Instances INNER JOIN Events"
                    + " ON (Events._id = Instances.event_id)"
                    + " WHERE Events._id =?)";
            db.delete("Instances", where, new String[]{"" + rowId});
        } else {
            // Creating or modifying a recurrence or exception.
            // Delete instances for recurrence (_sync_id = recurrenceSyncId)
            // and all exceptions (originalEvent = recurrenceSyncId)
            String where = "_id IN (SELECT Instances._id as _id"
                    + " FROM Instances INNER JOIN Events"
                    + " ON (Events._id = Instances.event_id)"
                    + " WHERE Events._sync_id =?"
                    + " OR Events.originalEvent =?)";
            db.delete("Instances", where, new String[]{recurrenceSyncId, recurrenceSyncId});
        }

        // Now do instance expansion
        Cursor entries = getRelevantRecurrenceEntries(recurrenceSyncId, rowId);
        try {
            performInstanceExpansion(fields.minInstance, fields.maxInstance, fields.timezone, entries);
        } finally {
            if (entries != null) {
                entries.close();
            }
        }

        // Clear busy bits
        mMetaData.writeLocked(fields.timezone, fields.minInstance, fields.maxInstance);
    }

    long calculateLastDate(ContentValues values)
            throws DateException {
        // Allow updates to some event fields like the title or hasAlarm
        // without requiring DTSTART.
        if (!values.containsKey(Events.DTSTART)) {
            if (values.containsKey(Events.DTEND) || values.containsKey(Events.RRULE)
                    || values.containsKey(Events.DURATION)
                    || values.containsKey(Events.EVENT_TIMEZONE)
                    || values.containsKey(Events.RDATE)
                    || values.containsKey(Events.EXRULE)
                    || values.containsKey(Events.EXDATE)) {
                throw new RuntimeException("DTSTART field missing from event");
            }
            return -1;
        }
        long dtstartMillis = values.getAsLong(Events.DTSTART);
        long lastMillis = -1;

        // Can we use dtend with a repeating event?  What does that even
        // mean?
        // NOTE: if the repeating event has a dtend, we convert it to a
        // duration during event processing, so this situation should not
        // occur.
        Long dtEnd = values.getAsLong(Events.DTEND);
        if (dtEnd != null) {
            lastMillis = dtEnd;
        } else {
            // find out how long it is
            Duration duration = new Duration();
            String durationStr = values.getAsString(Events.DURATION);
            if (durationStr != null) {
                duration.parse(durationStr);
            }

            RecurrenceSet recur = new RecurrenceSet(values);

            if (recur.hasRecurrence()) {
                // the event is repeating, so find the last date it
                // could appear on

                String tz = values.getAsString(Events.EVENT_TIMEZONE);

                if (TextUtils.isEmpty(tz)) {
                    // floating timezone
                    tz = Time.TIMEZONE_UTC;
                }
                Time dtstartLocal = new Time(tz);

                dtstartLocal.set(dtstartMillis);

                RecurrenceProcessor rp = new RecurrenceProcessor();
                lastMillis = rp.getLastOccurence(dtstartLocal, recur);
                if (lastMillis == -1) {
                    return lastMillis;  // -1
                }
            } else {
                // the event is not repeating, just use dtstartMillis
                lastMillis = dtstartMillis;
            }

            // that was the beginning of the event.  this is the end.
            lastMillis = duration.addTo(lastMillis);
        }
        return lastMillis;
    }

    private ContentValues updateContentValuesFromEvent(ContentValues initialValues) {
        try {
            ContentValues values = new ContentValues(initialValues);

            long last = calculateLastDate(values);
            if (last != -1) {
                values.put(Events.LAST_DATE, last);
            }

            return values;
        } catch (DateException e) {
            // don't add it if there was an error
            Log.w(TAG, "Could not calculate last date.", e);
            return null;
        }
    }

    private void updateEventRawTimesLocked(long eventId, ContentValues values) {
        ContentValues rawValues = new ContentValues();

        rawValues.put("event_id", eventId);

        String timezone = values.getAsString(Events.EVENT_TIMEZONE);

        boolean allDay = false;
        Integer allDayInteger = values.getAsInteger(Events.ALL_DAY);
        if (allDayInteger != null) {
            allDay = allDayInteger != 0;
        }

        if (allDay || TextUtils.isEmpty(timezone)) {
            // floating timezone
            timezone = Time.TIMEZONE_UTC;
        }

        Time time = new Time(timezone);
        time.allDay = allDay;
        Long dtstartMillis = values.getAsLong(Events.DTSTART);
        if (dtstartMillis != null) {
            time.set(dtstartMillis);
            rawValues.put("dtstart2445", time.format2445());
        }

        Long dtendMillis = values.getAsLong(Events.DTEND);
        if (dtendMillis != null) {
            time.set(dtendMillis);
            rawValues.put("dtend2445", time.format2445());
        }

        Long originalInstanceMillis = values.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
        if (originalInstanceMillis != null) {
            // This is a recurrence exception so we need to get the all-day
            // status of the original recurring event in order to format the
            // date correctly.
            allDayInteger = values.getAsInteger(Events.ORIGINAL_ALL_DAY);
            if (allDayInteger != null) {
                time.allDay = allDayInteger != 0;
            }
            time.set(originalInstanceMillis);
            rawValues.put("originalInstanceTime2445", time.format2445());
        }

        Long lastDateMillis = values.getAsLong(Events.LAST_DATE);
        if (lastDateMillis != null) {
            time.allDay = allDay;
            time.set(lastDateMillis);
            rawValues.put("lastDate2445", time.format2445());
        }

        mEventsRawTimesInserter.replace(rawValues);
    }

    @Override
    public int deleteInternal(Uri url, String where, String[] whereArgs) {
        final SQLiteDatabase db = getDatabase();
        int match = sURLMatcher.match(url);
        switch (match)
        {
            case EVENTS_ID:
            {
                String id = url.getLastPathSegment();
                if (where != null) {
                    throw new UnsupportedOperationException("CalendarProvider "
                            + "doesn't support where based deletion for type "
                            + match);
                }
                if (!isTemporary()) {

                    // Query this event to get the fields needed for inserting
                    // a new row in the DeletedEvents table.
                    Cursor cursor = db.query("Events", EVENTS_PROJECTION,
                            "_id=" + id, null, null, null, null);
                    try {
                        if (cursor.moveToNext()) {
                            String syncId = cursor.getString(EVENTS_SYNC_ID_INDEX);
                            if (!TextUtils.isEmpty(syncId)) {
                                String syncVersion = cursor.getString(EVENTS_SYNC_VERSION_INDEX);
                                String syncAccountName =
                                        cursor.getString(EVENTS_SYNC_ACCOUNT_NAME_INDEX);
                                String syncAccountType =
                                        cursor.getString(EVENTS_SYNC_ACCOUNT_TYPE_INDEX);
                                Long calId = cursor.getLong(EVENTS_CALENDAR_ID_INDEX);

                                ContentValues values = new ContentValues();
                                values.put(Events._SYNC_ID, syncId);
                                values.put(Events._SYNC_VERSION, syncVersion);
                                values.put(Events._SYNC_ACCOUNT, syncAccountName);
                                values.put(Events._SYNC_ACCOUNT_TYPE, syncAccountType);
                                values.put(Events.CALENDAR_ID, calId);
                                mDeletedEventsInserter.insert(values);

                                // TODO: we may also want to delete exception
                                // events for this event (in case this was a
                                // recurring event).  We can do that with the
                                // following code:
                                // db.delete("Events", "originalEvent=?", new String[] {syncId});
                            }

                            // If this was a recurring event or a recurrence
                            // exception, then force a recalculation of the
                            // instances.
                            String rrule = cursor.getString(EVENTS_RRULE_INDEX);
                            String rdate = cursor.getString(EVENTS_RDATE_INDEX);
                            String origEvent = cursor.getString(EVENTS_ORIGINAL_EVENT_INDEX);
                            if (!TextUtils.isEmpty(rrule) || !TextUtils.isEmpty(rdate)
                                    || !TextUtils.isEmpty(origEvent)) {
                                mMetaData.clearInstanceRange();
                            }
                        }
                    } finally {
                        cursor.close();
                        cursor = null;
                    }

                    scheduleNextAlarm(false /* do not remove alarms */);
                    triggerAppWidgetUpdate(-1);
                }

                // There is a delete trigger that will cause all instances
                // matching this event id to get deleted as well.  In fact, all
                // of the following tables will remove entries matching this
                // event id: Instances, EventsRawTimes, Attendees, Reminders,
                // CalendarAlerts, and ExtendedProperties.
                int result = db.delete("Events", "_id=" + id, null);
                return result;
            }
            case ATTENDEES:
            {
                int result = db.delete("Attendees", where, whereArgs);
                return result;
            }
            case ATTENDEES_ID:
            {
                // we currently don't support deletions to the attendees list.
                // TODO: remove this restriction when we handle the full attendees
                // feed.  we'll need to put in some logic to check that the
                // modification will be allowed by the server.
                throw new IllegalArgumentException("Cannot delete attendees.");
                //                String id = url.getPathSegments().get(1);
                //                int result = db.delete("Attendees", "_id="+id, null);
                //                return result;
            }
            case REMINDERS:
            {
                int result = db.delete("Reminders", where, whereArgs);
                return result;
            }
            case REMINDERS_ID:
            {
                String id = url.getLastPathSegment();
                int result = db.delete("Reminders", "_id="+id, null);
                return result;
            }
            case CALENDAR_ALERTS:
            {
                int result = db.delete("CalendarAlerts", where, whereArgs);
                return result;
            }
            case CALENDAR_ALERTS_ID:
            {
                String id = url.getLastPathSegment();
                int result = db.delete("CalendarAlerts", "_id="+id, null);
                return result;
            }
            case DELETED_EVENTS:
            case EVENTS:
                throw new UnsupportedOperationException("Cannot delete that URL");
            case CALENDARS_ID:
                StringBuilder whereSb = new StringBuilder("_id=");
                whereSb.append(url.getPathSegments().get(1));
                if (!TextUtils.isEmpty(where)) {
                    whereSb.append(" AND (");
                    whereSb.append(where);
                    whereSb.append(')');
                }
                where = whereSb.toString();
                // fall through to CALENDARS for the actual delete
            case CALENDARS:
                return deleteMatchingCalendars(where);
            case INSTANCES:
            case INSTANCES_BY_DAY:
            case EVENT_DAYS:
                throw new UnsupportedOperationException("Cannot delete that URL");
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    private int deleteMatchingCalendars(String where) {
        // query to find all the calendars that match, for each
        // - delete calendar subscription
        // - delete calendar

        int numDeleted = 0;
        final SQLiteDatabase db = getDatabase();
        Cursor c = db.query("Calendars", sCalendarsIdProjection, where, null,
                null, null, null);
        if (c == null) {
            return 0;
        }
        try {
            while (c.moveToNext()) {
                long id = c.getLong(CALENDARS_INDEX_ID);
                if (!isTemporary()) {
                    modifyCalendarSubscription(id, false /* not selected */);
                }
                c.deleteRow();
                numDeleted++;
            }
        } finally {
            c.close();
        }
        return numDeleted;
    }

    // TODO: call calculateLastDate()!
    @Override
    public int updateInternal(Uri url, ContentValues values,
            String where, String[] selectionArgs) {
        int match = sURLMatcher.match(url);

        // TODO: remove this restriction
        if (!TextUtils.isEmpty(where) && match != CALENDAR_ALERTS) {
            throw new IllegalArgumentException(
                    "WHERE based updates not supported");
        }
        final SQLiteDatabase db = getDatabase();

        switch (match) {
            case CALENDARS_ID:
            {
                long id = ContentUris.parseId(url);
                Integer syncEvents = values.getAsInteger(Calendars.SYNC_EVENTS);
                if (syncEvents != null && !isTemporary()) {
                    modifyCalendarSubscription(id, syncEvents == 1);
                }

                int result = db.update("Calendars", values, "_id="+ id, null);

                return result;
            }
            case EVENTS_ID:
            {
                long id = ContentUris.parseId(url);
                if (!isTemporary()) {
                    values.put(Events._SYNC_DIRTY, 1);

                    // Disallow updating the attendee status in the Events
                    // table.  In the future, we could support this but we
                    // would have to query and update the attendees table
                    // to keep the values consistent.
                    if (values.containsKey(Events.SELF_ATTENDEE_STATUS)) {
                        throw new IllegalArgumentException("Updating "
                                + Events.SELF_ATTENDEE_STATUS
                                + " in Events table is not allowed.");
                    }

                    if (values.containsKey(Events.HTML_URI)) {
                        throw new IllegalArgumentException("Updating "
                                + Events.HTML_URI
                                + " in Events table is not allowed.");
                    }

                }

                ContentValues updatedValues = updateContentValuesFromEvent(values);
                if (updatedValues == null) {
                    Log.w(TAG, "Could not update event.");
                    return 0;
                }

                int result = db.update("Events", updatedValues, "_id="+id, null);
                if (!isTemporary()) {
                    if (result > 0) {
                        updateEventRawTimesLocked(id, updatedValues);
                        updateInstancesLocked(updatedValues, id, false /* not a new event */, db);

                        if (values.containsKey(Events.DTSTART)) {
                            // The start time of the event changed, so run the
                            // event alarm scheduler.
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "updateInternal() changing event");
                            }
                            scheduleNextAlarm(false /* do not remove alarms */);
                            triggerAppWidgetUpdate(id);
                        }
                    }
                }
                return result;
            }
            case ATTENDEES_ID:
            {
                // Copy the attendee status value to the Events table.
                updateEventAttendeeStatus(db, values);

                long id = ContentUris.parseId(url);
                return db.update("Attendees", values, "_id="+id, null);
            }
            case CALENDAR_ALERTS_ID:
            {
                long id = ContentUris.parseId(url);
                return db.update("CalendarAlerts", values, "_id="+id, null);
            }
            case CALENDAR_ALERTS:
            {
                return db.update("CalendarAlerts", values, where, null);
            }
            case REMINDERS_ID:
            {
                long id = ContentUris.parseId(url);
                int result = db.update("Reminders", values, "_id="+id, null);
                if (!isTemporary()) {
                    // Reschedule the event alarms because the
                    // "minutes" field may have changed.
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "updateInternal() changing reminder");
                    }
                    scheduleNextAlarm(false /* do not remove alarms */);
                }
                return result;
            }
            case EXTENDED_PROPERTIES_ID:
            {
                long id = ContentUris.parseId(url);
                return db.update("ExtendedProperties", values, "_id="+id, null);
            }
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    /**
     * Schedule a calendar sync for the account.
     * @param account the account for which to schedule a sync
     * @param uploadChangesOnly if set, specify that the sync should only send
     *   up local changes
     * @param url the url feed for the calendar to sync (may be null)
     */
    private void scheduleSync(Account account, boolean uploadChangesOnly, String url) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, uploadChangesOnly);
        if (url != null) {
            extras.putString("feed", url);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        }
        ContentResolver.requestSync(account, Calendars.CONTENT_URI.getAuthority(), extras);
    }

    private void modifyCalendarSubscription(long id, boolean syncEvents) {
        // get the account, url, and current selected state
        // for this calendar.
        Cursor cursor = query(ContentUris.withAppendedId(Calendars.CONTENT_URI, id),
                new String[] {Calendars._SYNC_ACCOUNT, Calendars._SYNC_ACCOUNT_TYPE,
                        Calendars.URL, Calendars.SYNC_EVENTS},
                null /* selection */,
                null /* selectionArgs */,
                null /* sort */);

        Account account = null;
        String calendarUrl = null;
        boolean oldSyncEvents = false;
        if (cursor != null && cursor.moveToFirst()) {
            try {
                final String accountName = cursor.getString(0);
                final String accountType = cursor.getString(1);
                account = new Account(accountName, accountType);
                calendarUrl = cursor.getString(2);
                oldSyncEvents = (cursor.getInt(3) != 0);
            } finally {
                cursor.close();
            }
        }

        if (account == null || TextUtils.isEmpty(calendarUrl)) {
            // should not happen?
            Log.w(TAG, "Cannot update subscription because account "
                    + "or calendar url empty -- should not happen.");
            return;
        }

        if (oldSyncEvents == syncEvents) {
            // nothing to do
            return;
        }

        // If we are no longer syncing a calendar then make sure that the
        // old calendar sync data is cleared.  Then if we later add this
        // calendar back, we will sync all the events.
        if (!syncEvents) {
            byte[] data = readSyncDataBytes(account);
            GDataSyncData syncData = AbstractGDataSyncAdapter.newGDataSyncDataFromBytes(data);
            if (syncData != null) {
                syncData.feedData.remove(calendarUrl);
                data = AbstractGDataSyncAdapter.newBytesFromGDataSyncData(syncData);
                writeSyncDataBytes(account, data);
            }

            // Delete all of the events in this calendar to save space.
            // This is the closest we can come to deleting a calendar.
            // Clients should never actually delete a calendar.  That won't
            // work.  We need to keep the calendar entry in the Calendars table
            // in order to know not to sync the events for that calendar from
            // the server.
            final SQLiteDatabase db = getDatabase();
            String[] args = new String[] {Long.toString(id)};
            db.delete("Events", CALENDAR_ID_SELECTION, args);
            // Note that we do not delete the matching entries
            // in the DeletedEvents table.  We will let those
            // deleted events propagate to the server.

            // TODO: cancel any pending/ongoing syncs for this calendar.

            // TODO: there is a corner case to deal with here: namely, if
            // we edit or delete an event on the phone and then remove
            // (that is, stop syncing) a calendar, and if we also make a
            // change on the server to that event at about the same time,
            // then we will never propagate the changes from the phone to
            // the server.
        }

        // If the calendar is not selected for syncing, then don't download
        // events.
        scheduleSync(account, !syncEvents, calendarUrl);
    }

    @Override
    public void onSyncStop(boolean success) {
        super.onSyncStop(success);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onSyncStop() success: " + success);
        }
        scheduleNextAlarm(false /* do not remove alarms */);
        triggerAppWidgetUpdate(-1);
    }

    @Override
    protected Iterable<EventMerger> getMergers() {
        return Collections.singletonList(new EventMerger());
    }

    /**
     * Update any existing widgets with the changed events.
     *
     * @param changedEventId Specific event known to be changed, otherwise -1.
     *            If present, we use it to decide if an update is necessary.
     */
    private synchronized void triggerAppWidgetUpdate(long changedEventId) {
        Context context = getContext();
        if (context != null) {
            mAppWidgetProvider.providerUpdated(context, changedEventId);
        }
    }

    void bootCompleted() {
        // Remove alarms from the CalendarAlerts table that have been marked
        // as "scheduled" but not fired yet.  We do this because the
        // AlarmManagerService loses all information about alarms when the
        // power turns off but we store the information in a database table
        // that persists across reboots. See the documentation for
        // scheduleNextAlarmLocked() for more information.
        scheduleNextAlarm(true /* remove alarms */);
    }

    /* Retrieve and cache the alarm manager */
    private AlarmManager getAlarmManager() {
        synchronized(mAlarmLock) {
            if (mAlarmManager == null) {
                Context context = getContext();
                if (context == null) {
                    Log.e(TAG, "getAlarmManager() cannot get Context");
                    return null;
                }
                Object service = context.getSystemService(Context.ALARM_SERVICE);
                mAlarmManager = (AlarmManager) service;
            }
            return mAlarmManager;
        }
    }

    void scheduleNextAlarmCheck(long triggerTime) {
        AlarmManager manager = getAlarmManager();
        if (manager == null) {
            Log.e(TAG, "scheduleNextAlarmCheck() cannot get AlarmManager");
            return;
        }
        Context context = getContext();
        Intent intent = new Intent(CalendarReceiver.SCHEDULE);
        intent.setClass(context, CalendarReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(context,
                0, intent, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            // Cancel any previous alarms that do the same thing.
            manager.cancel(pending);
        }
        pending = PendingIntent.getBroadcast(context,
                0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Time time = new Time();
            time.set(triggerTime);
            String timeStr = time.format(" %a, %b %d, %Y %I:%M%P");
            Log.d(TAG, "scheduleNextAlarmCheck at: " + triggerTime + timeStr);
        }

        manager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending);
    }

    /*
     * This method runs the alarm scheduler in a background thread.
     */
    void scheduleNextAlarm(boolean removeAlarms) {
        Thread thread = new AlarmScheduler(removeAlarms);
        thread.start();
    }

    /**
     * This method runs in a background thread and schedules an alarm for
     * the next calendar event, if necessary.
     */
    private void runScheduleNextAlarm(boolean removeAlarms) {
        // Do not schedule any alarms if this is a temporary database.
        if (isTemporary()) {
            return;
        }

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (removeAlarms) {
                removeScheduledAlarmsLocked(db);
            }
            scheduleNextAlarmLocked(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * This method looks at the 24-hour window from now for any events that it
     * needs to schedule.  This method runs within a database transaction. It
     * also runs in a background thread.
     *
     * The CalendarProvider keeps track of which alarms it has already scheduled
     * to avoid scheduling them more than once and for debugging problems with
     * alarms.  It stores this knowledge in a database table called CalendarAlerts
     * which persists across reboots.  But the actual alarm list is in memory
     * and disappears if the phone loses power.  To avoid missing an alarm, we
     * clear the entries in the CalendarAlerts table when we start up the
     * CalendarProvider.
     *
     * Scheduling an alarm multiple times is not tragic -- we filter out the
     * extra ones when we receive them. But we still need to keep track of the
     * scheduled alarms. The main reason is that we need to prevent multiple
     * notifications for the same alarm (on the receive side) in case we
     * accidentally schedule the same alarm multiple times.  We don't have
     * visibility into the system's alarm list so we can never know for sure if
     * we have already scheduled an alarm and it's better to err on scheduling
     * an alarm twice rather than missing an alarm.  Another reason we keep
     * track of scheduled alarms in a database table is that it makes it easy to
     * run an SQL query to find the next reminder that we haven't scheduled.
     *
     * @param db the database
     */
    private void scheduleNextAlarmLocked(SQLiteDatabase db) {
        AlarmManager alarmManager = getAlarmManager();
        if (alarmManager == null) {
            Log.e(TAG, "Failed to find the AlarmManager. Could not schedule the next alarm!");
            return;
        }

        final long currentMillis = System.currentTimeMillis();
        final long start = currentMillis - SCHEDULE_ALARM_SLACK;
        final long end = start + (24 * 60 * 60 * 1000);
        ContentResolver cr = getContext().getContentResolver();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Time time = new Time();
            time.set(start);
            String startTimeStr = time.format(" %a, %b %d, %Y %I:%M%P");
            Log.d(TAG, "runScheduleNextAlarm() start search: " + startTimeStr);
        }

        // Delete rows in CalendarAlert where the corresponding Instance or
        // Reminder no longer exist.
        // Also clear old alarms but keep alarms around for a while to prevent
        // multiple alerts for the same reminder.  The "clearUpToTime'
        // should be further in the past than the point in time where
        // we start searching for events (the "start" variable defined above).
        String selectArg[] = new String[] {
            Long.toString(currentMillis - CLEAR_OLD_ALARM_THRESHOLD)
        };

        int rowsDeleted =
            db.delete(CalendarAlerts.TABLE_NAME, INVALID_CALENDARALERTS_SELECTOR, selectArg);

        long nextAlarmTime = end;
        final long tmpAlarmTime = CalendarAlerts.findNextAlarmTime(cr, currentMillis);
        if (tmpAlarmTime != -1 && tmpAlarmTime < nextAlarmTime) {
            nextAlarmTime = tmpAlarmTime;
        }

        // Extract events from the database sorted by alarm time.  The
        // alarm times are computed from Instances.begin (whose units
        // are milliseconds) and Reminders.minutes (whose units are
        // minutes).
        //
        // Also, ignore events whose end time is already in the past.
        // Also, ignore events alarms that we have already scheduled.
        //
        // Note 1: we can add support for the case where Reminders.minutes
        // equals -1 to mean use Calendars.minutes by adding a UNION for
        // that case where the two halves restrict the WHERE clause on
        // Reminders.minutes != -1 and Reminders.minutes = 1, respectively.
        //
        // Note 2: we have to name "myAlarmTime" different from the
        // "alarmTime" column in CalendarAlerts because otherwise the
        // query won't find multiple alarms for the same event.
        String query = "SELECT begin-(minutes*60000) AS myAlarmTime,"
                + " Instances.event_id AS eventId, begin, end,"
                + " title, allDay, method, minutes"
                + " FROM Instances INNER JOIN Events"
                + " ON (Events._id = Instances.event_id)"
                + " INNER JOIN Reminders"
                + " ON (Instances.event_id = Reminders.event_id)"
                + " WHERE method=" + Reminders.METHOD_ALERT
                + " AND myAlarmTime>=" + start
                + " AND myAlarmTime<=" + nextAlarmTime
                + " AND end>=" + currentMillis
                + " AND 0=(SELECT count(*) from CalendarAlerts CA"
                + " where CA.event_id=Instances.event_id AND CA.begin=Instances.begin"
                + " AND CA.alarmTime=myAlarmTime)"
                + " ORDER BY myAlarmTime,begin,title";

        acquireInstanceRangeLocked(start, end, false /* don't use minimum expansion windows */);
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(query, null);

            final int beginIndex = cursor.getColumnIndex(Instances.BEGIN);
            final int endIndex = cursor.getColumnIndex(Instances.END);
            final int eventIdIndex = cursor.getColumnIndex("eventId");
            final int alarmTimeIndex = cursor.getColumnIndex("myAlarmTime");
            final int minutesIndex = cursor.getColumnIndex(Reminders.MINUTES);

            if (DEBUG) {
                Time time = new Time();
                time.set(nextAlarmTime);
                String alarmTimeStr = time.format(" %a, %b %d, %Y %I:%M%P");
                Log.d(TAG, "cursor results: " + cursor.getCount() + " nextAlarmTime: "
                        + alarmTimeStr);
            }

            while (cursor.moveToNext()) {
                // Schedule all alarms whose alarm time is as early as any
                // scheduled alarm.  For example, if the earliest alarm is at
                // 1pm, then we will schedule all alarms that occur at 1pm
                // but no alarms that occur later than 1pm.
                // Actually, we allow alarms up to a minute later to also
                // be scheduled so that we don't have to check immediately
                // again after an event alarm goes off.
                final long alarmTime = cursor.getLong(alarmTimeIndex);
                final long eventId = cursor.getLong(eventIdIndex);
                final int minutes = cursor.getInt(minutesIndex);
                final long startTime = cursor.getLong(beginIndex);
                final long endTime = cursor.getLong(endIndex);

                if (DEBUG) {
                    Time time = new Time();
                    time.set(alarmTime);
                    String schedTime = time.format(" %a, %b %d, %Y %I:%M%P");
                    time.set(startTime);
                    String startTimeStr = time.format(" %a, %b %d, %Y %I:%M%P");
                    time.set(endTime);
                    String endTimeStr = time.format(" - %a, %b %d, %Y %I:%M%P");
                    time.set(currentMillis);
                    String currentTimeStr = time.format(" %a, %b %d, %Y %I:%M%P");

                    Log.d(TAG, "  looking at id: " + eventId + " " + startTime + startTimeStr
                            + " alarm: " + alarmTime + schedTime);
                }

                if (alarmTime < nextAlarmTime) {
                    nextAlarmTime = alarmTime;
                } else if (alarmTime > nextAlarmTime + android.text.format.DateUtils.MINUTE_IN_MILLIS) {
                    // This event alarm (and all later ones) will be scheduled
                    // later.
                    if (DEBUG) {
                        Log.d(TAG, "This event alarm (and all later ones) will be scheduled later");
                    }
                    break;
                }

                // Avoid an SQLiteContraintException by checking if this alarm
                // already exists in the table.
                if (CalendarAlerts.alarmExists(cr, eventId, startTime, alarmTime)) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        int titleIndex = cursor.getColumnIndex(Events.TITLE);
                        String title = cursor.getString(titleIndex);
                        Log.d(TAG, "  alarm exists for id: " + eventId + " " + title);
                    }
                    continue;
                }

                // Insert this alarm into the CalendarAlerts table
                Uri uri = CalendarAlerts.insert(cr, eventId, startTime,
                        endTime, alarmTime, minutes);
                if (uri == null) {
                    Log.e(TAG, "runScheduleNextAlarm() insert into CalendarAlerts table failed");
                    continue;
                }

                CalendarAlerts.scheduleAlarm(getContext(), alarmManager, alarmTime);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Refresh notification bar
        if (rowsDeleted > 0) {
            CalendarAlerts.scheduleAlarm(getContext(), alarmManager, currentMillis);
        }

        // If we scheduled an event alarm, then schedule the next alarm check
        // for one minute past that alarm.  Otherwise, if there were no
        // event alarms scheduled, then check again in 24 hours.  If a new
        // event is inserted before the next alarm check, then this method
        // will be run again when the new event is inserted.
        if (nextAlarmTime != Long.MAX_VALUE) {
            scheduleNextAlarmCheck(nextAlarmTime + android.text.format.DateUtils.MINUTE_IN_MILLIS);
        } else {
            scheduleNextAlarmCheck(currentMillis + android.text.format.DateUtils.DAY_IN_MILLIS);
        }
    }

    /**
     * Removes the entries in the CalendarAlerts table for alarms that we have
     * scheduled but that have not fired yet. We do this to ensure that we
     * don't miss an alarm.  The CalendarAlerts table keeps track of the
     * alarms that we have scheduled but the actual alarm list is in memory
     * and will be cleared if the phone reboots.
     *
     * We don't need to remove entries that have already fired, and in fact
     * we should not remove them because we need to display the notifications
     * until the user dismisses them.
     *
     * We could remove entries that have fired and been dismissed, but we leave
     * them around for a while because it makes it easier to debug problems.
     * Entries that are old enough will be cleaned up later when we schedule
     * new alarms.
     */
    private void removeScheduledAlarmsLocked(SQLiteDatabase db) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "removing scheduled alarms");
        }
        db.delete(CalendarAlerts.TABLE_NAME,
                CalendarAlerts.STATE + "=" + CalendarAlerts.SCHEDULED, null /* whereArgs */);
    }

    private static String sEventsTable = "Events";
    private static String sDeletedEventsTable = "DeletedEvents";
    private static String sAttendeesTable = "Attendees";
    private static String sRemindersTable = "Reminders";
    private static String sCalendarAlertsTable = "CalendarAlerts";
    private static String sExtendedPropertiesTable = "ExtendedProperties";

    private class EventMerger extends AbstractTableMerger {

        private ContentValues mValues = new ContentValues();
        EventMerger() {
            super(getDatabase(), sEventsTable, Calendar.Events.CONTENT_URI,
                    sDeletedEventsTable, Calendar.Events.DELETED_CONTENT_URI);
        }

        @Override
        protected void notifyChanges() {
            getContext().getContentResolver().notifyChange(Events.CONTENT_URI,
                    null /* observer */, false /* do not sync to network */);
        }

        @Override
        protected void cursorRowToContentValues(Cursor cursor, ContentValues map) {
            rowToContentValues(cursor, map);
        }

        @Override
        public void insertRow(ContentProvider diffs, Cursor diffsCursor) {
            rowToContentValues(diffsCursor, mValues);
            final SQLiteDatabase db = getDatabase();
            long rowId = mEventsInserter.insert(mValues);
            if (rowId <= 0) {
                Log.e(TAG, "Unable to insert values into calendar db: " + mValues);
                return;
            }

            long diffsRowId = diffsCursor.getLong(
                    diffsCursor.getColumnIndex(Events._ID));

            insertAttendees(diffs, diffsRowId, rowId, db);
            insertRemindersIfNecessary(diffs, diffsRowId, rowId, db);
            insertExtendedPropertiesIfNecessary(diffs, diffsRowId, rowId, db);
            updateEventRawTimesLocked(rowId, mValues);
            updateInstancesLocked(mValues, rowId, true /* new event */, db);

            // Update the _SYNC_DIRTY flag of the event. We have to do this
            // after inserting since the update of the reminders and extended properties
            // methods will fire a sql trigger that will cause this flag to
            // be set.
            clearSyncDirtyFlag(db, rowId);
        }

        private void clearSyncDirtyFlag(SQLiteDatabase db, long rowId) {
            mValues.clear();
            mValues.put(Events._SYNC_DIRTY, 0);
            db.update(mTable, mValues, Events._ID + '=' + rowId, null);
        }

        private void insertAttendees(ContentProvider diffs,
                long diffsRowId,
                long rowId,
                SQLiteDatabase db) {
            // query attendees in diffs
            Cursor attendeesCursor =
                    diffs.query(Attendees.CONTENT_URI, null,
                            "event_id=" + diffsRowId, null, null);
            ContentValues attendeesValues = new ContentValues();
            try {
                while (attendeesCursor.moveToNext()) {
                    attendeesValues.clear();
                    DatabaseUtils.cursorStringToContentValues(attendeesCursor,
                            Attendees.ATTENDEE_NAME,
                            attendeesValues);
                    DatabaseUtils.cursorStringToContentValues(attendeesCursor,
                            Attendees.ATTENDEE_EMAIL,
                            attendeesValues);
                    DatabaseUtils.cursorIntToContentValues(attendeesCursor,
                            Attendees.ATTENDEE_STATUS,
                            attendeesValues);
                    DatabaseUtils.cursorIntToContentValues(attendeesCursor,
                            Attendees.ATTENDEE_TYPE,
                            attendeesValues);
                    DatabaseUtils.cursorIntToContentValues(attendeesCursor,
                            Attendees.ATTENDEE_RELATIONSHIP,
                            attendeesValues);
                    attendeesValues.put(Attendees.EVENT_ID, rowId);
                    mAttendeesInserter.insert(attendeesValues);
                }
            } finally {
                if (attendeesCursor != null) {
                    attendeesCursor.close();
                }
            }
        }

        private void insertRemindersIfNecessary(ContentProvider diffs,
                long diffsRowId,
                long rowId,
                SQLiteDatabase db) {
            // insert reminders, if necessary.
            Integer hasAlarm = mValues.getAsInteger(Events.HAS_ALARM);
            if (hasAlarm != null && hasAlarm.intValue() == 1) {
                // query reminders in diffs
                Cursor reminderCursor =
                        diffs.query(Reminders.CONTENT_URI, null,
                                "event_id=" + diffsRowId, null, null);
                ContentValues reminderValues = new ContentValues();
                try {
                    while (reminderCursor.moveToNext()) {
                        reminderValues.clear();
                        DatabaseUtils.cursorIntToContentValues(reminderCursor,
                                Reminders.METHOD,
                                reminderValues);
                        DatabaseUtils.cursorIntToContentValues(reminderCursor,
                                Reminders.MINUTES,
                                reminderValues);
                        reminderValues.put(Reminders.EVENT_ID, rowId);
                        mRemindersInserter.insert(reminderValues);
                    }
                } finally {
                    if (reminderCursor != null) {
                        reminderCursor.close();
                    }
                }
            }
        }

        private void insertExtendedPropertiesIfNecessary(ContentProvider diffs,
                long diffsRowId,
                long rowId,
                SQLiteDatabase db) {
            // insert extended properties, if necessary.
            Integer hasExtendedProperties = mValues.getAsInteger(Events.HAS_EXTENDED_PROPERTIES);
            if (hasExtendedProperties != null && hasExtendedProperties.intValue() != 0) {
                // query reminders in diffs
                Cursor extendedPropertiesCursor =
                        diffs.query(Calendar.ExtendedProperties.CONTENT_URI, null,
                                "event_id=" + diffsRowId, null, null);
                ContentValues extendedPropertiesValues = new ContentValues();
                try {
                    while (extendedPropertiesCursor.moveToNext()) {
                        extendedPropertiesValues.clear();
                        DatabaseUtils.cursorStringToContentValues(extendedPropertiesCursor,
                                Calendar.ExtendedProperties.NAME, extendedPropertiesValues);
                        DatabaseUtils.cursorStringToContentValues(extendedPropertiesCursor,
                                Calendar.ExtendedProperties.VALUE, extendedPropertiesValues);
                        extendedPropertiesValues.put(ExtendedProperties.EVENT_ID, rowId);
                        mExtendedPropertiesInserter.insert(extendedPropertiesValues);
                    }
                } finally {
                    if (extendedPropertiesCursor != null) {
                        extendedPropertiesCursor.close();
                    }
                }
            }
        }

        @Override
        public void updateRow(long localId, ContentProvider diffs,
                Cursor diffsCursor) {
            rowToContentValues(diffsCursor, mValues);
            final SQLiteDatabase db = getDatabase();
            int numRows = db.update(mTable, mValues, "_id=" + localId, null /* selectionArgs */);

            if (numRows <= 0) {
                Log.e(TAG, "Unable to update calendar db: " + mValues);
                return;
            }

            long diffsRowId = diffsCursor.getLong(
                    diffsCursor.getColumnIndex(Events._ID));
            // TODO: only update the attendees, reminders, and extended properties if they have
            // changed?
            // delete the existing attendees, reminders, and extended properties
            db.delete(sAttendeesTable, "event_id=" + localId, null /* selectionArgs */);
            db.delete(sRemindersTable, "event_id=" + localId, null /* selectionArgs */);
            db.delete(sExtendedPropertiesTable, "event_id=" + localId,
                    null /* selectionArgs */);

            // process attendees sent by the server.
            insertAttendees(diffs, diffsRowId, localId, db);
            // process reminders sent by the server.
            insertRemindersIfNecessary(diffs, diffsRowId, localId, db);

            // process extended properties sent by the server.
            insertExtendedPropertiesIfNecessary(diffs, diffsRowId, localId, db);

            updateEventRawTimesLocked(localId, mValues);
            updateInstancesLocked(mValues, localId, false /* not a new event */, db);

            // Update the _SYNC_DIRTY flag of the event. We have to do this
            // after updating since the update of the reminders and extended properties
            // methods will fire a sql trigger that will cause this flag to
            // be set.
            clearSyncDirtyFlag(db, localId);
        }

        @Override
        public void resolveRow(long localId, String syncId,
                ContentProvider diffs, Cursor diffsCursor) {
            // server wins
            updateRow(localId, diffs, diffsCursor);
        }

        @Override
        public void deleteRow(Cursor localCursor) {
            long localId = localCursor.getLong(localCursor.getColumnIndexOrThrow(Events._ID));

            // we have to read this row from the DB since the projection that is used
            // by cursor doesn't necessarily contain the columns we need
            Cursor c = getDatabase().query(sEventsTable,
                    new String[]{Events.RRULE, Events.RDATE, Events.ORIGINAL_EVENT},
                    "_id=" + localId, null, null, null, null);
            try {
                c.moveToNext();
                // If this was a recurring event or a recurrence exception, then
                // force a recalculation of the instances.
                // We can get a tombstoned recurrence exception
                // that doesn't have a rrule, rdate, or originalEvent, and the
                // check below wouldn't catch that.  However, in practice we also
                // get a different event with a rrule in that case, so the
                // instances get cleared by that rule.
                // This should be re-evaluated when calendar supports gd:deleted.
                String rrule = c.getString(c.getColumnIndexOrThrow(Events.RRULE));
                String rdate = c.getString(c.getColumnIndexOrThrow(Events.RDATE));
                String origEvent = c.getString(c.getColumnIndexOrThrow(Events.ORIGINAL_EVENT));
                if (!TextUtils.isEmpty(rrule) || !TextUtils.isEmpty(rdate)
                        || !TextUtils.isEmpty(origEvent)) {
                    mMetaData.clearInstanceRange();
                }
            } finally {
                c.close();
            }
            super.deleteRow(localCursor);
        }

        private void rowToContentValues(Cursor diffsCursor, ContentValues values) {
            values.clear();

            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events._SYNC_ID, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events._SYNC_TIME, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events._SYNC_VERSION, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events._SYNC_DIRTY, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events._SYNC_ACCOUNT, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    Events._SYNC_ACCOUNT_TYPE, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.HTML_URI, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.TITLE, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.EVENT_LOCATION, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.DESCRIPTION, values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.STATUS, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.SELF_ATTENDEE_STATUS,
                    values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.COMMENTS_URI, values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.DTSTART, values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.DTEND, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.EVENT_TIMEZONE, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.DURATION, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.ALL_DAY, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.VISIBILITY, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.TRANSPARENCY, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.HAS_ALARM, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.HAS_EXTENDED_PROPERTIES,
                    values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.RRULE, values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.ORIGINAL_EVENT, values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.ORIGINAL_INSTANCE_TIME,
                    values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.ORIGINAL_ALL_DAY,
                    values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.LAST_DATE, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.HAS_ATTENDEE_DATA, values);
            DatabaseUtils.cursorLongToContentValues(diffsCursor, Events.CALENDAR_ID, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.GUESTS_CAN_INVITE_OTHERS,
                    values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.GUESTS_CAN_MODIFY, values);
            DatabaseUtils.cursorIntToContentValues(diffsCursor, Events.GUESTS_CAN_SEE_GUESTS,
                    values);
            DatabaseUtils.cursorStringToContentValues(diffsCursor, Events.ORGANIZER, values);
        }
    }

    private static final int EVENTS = 1;
    private static final int EVENTS_ID = 2;
    private static final int INSTANCES = 3;
    private static final int DELETED_EVENTS = 4;
    private static final int CALENDARS = 5;
    private static final int CALENDARS_ID = 6;
    private static final int ATTENDEES = 7;
    private static final int ATTENDEES_ID = 8;
    private static final int REMINDERS = 9;
    private static final int REMINDERS_ID = 10;
    private static final int EXTENDED_PROPERTIES = 11;
    private static final int EXTENDED_PROPERTIES_ID = 12;
    private static final int CALENDAR_ALERTS = 13;
    private static final int CALENDAR_ALERTS_ID = 14;
    private static final int CALENDAR_ALERTS_BY_INSTANCE = 15;
    private static final int INSTANCES_BY_DAY = 16;
    private static final int EVENT_DAYS = 17;

    private static final UriMatcher sURLMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static final HashMap<String, String> sInstancesProjectionMap;
    private static final HashMap<String, String> sEventsProjectionMap;
    private static final HashMap<String, String> sAttendeesProjectionMap;
    private static final HashMap<String, String> sRemindersProjectionMap;
    private static final HashMap<String, String> sCalendarAlertsProjectionMap;

    static {
        sURLMatcher.addURI("calendar", "instances/when/*/*", INSTANCES);
        sURLMatcher.addURI("calendar", "instances/whenbyday/*/*", INSTANCES_BY_DAY);
        sURLMatcher.addURI("calendar", "instances/groupbyday/*/*", EVENT_DAYS);
        sURLMatcher.addURI("calendar", "events", EVENTS);
        sURLMatcher.addURI("calendar", "events/#", EVENTS_ID);
        sURLMatcher.addURI("calendar", "calendars", CALENDARS);
        sURLMatcher.addURI("calendar", "calendars/#", CALENDARS_ID);
        sURLMatcher.addURI("calendar", "deleted_events", DELETED_EVENTS);
        sURLMatcher.addURI("calendar", "attendees", ATTENDEES);
        sURLMatcher.addURI("calendar", "attendees/#", ATTENDEES_ID);
        sURLMatcher.addURI("calendar", "reminders", REMINDERS);
        sURLMatcher.addURI("calendar", "reminders/#", REMINDERS_ID);
        sURLMatcher.addURI("calendar", "extendedproperties", EXTENDED_PROPERTIES);
        sURLMatcher.addURI("calendar", "extendedproperties/#", EXTENDED_PROPERTIES_ID);
        sURLMatcher.addURI("calendar", "calendar_alerts", CALENDAR_ALERTS);
        sURLMatcher.addURI("calendar", "calendar_alerts/#", CALENDAR_ALERTS_ID);
        sURLMatcher.addURI("calendar", "calendar_alerts/by_instance", CALENDAR_ALERTS_BY_INSTANCE);


        sEventsProjectionMap = new HashMap<String, String>();
        // Events columns
        sEventsProjectionMap.put(Events.HTML_URI, "htmlUri");
        sEventsProjectionMap.put(Events.TITLE, "title");
        sEventsProjectionMap.put(Events.EVENT_LOCATION, "eventLocation");
        sEventsProjectionMap.put(Events.DESCRIPTION, "description");
        sEventsProjectionMap.put(Events.STATUS, "eventStatus");
        sEventsProjectionMap.put(Events.SELF_ATTENDEE_STATUS, "selfAttendeeStatus");
        sEventsProjectionMap.put(Events.COMMENTS_URI, "commentsUri");
        sEventsProjectionMap.put(Events.DTSTART, "dtstart");
        sEventsProjectionMap.put(Events.DTEND, "dtend");
        sEventsProjectionMap.put(Events.EVENT_TIMEZONE, "eventTimezone");
        sEventsProjectionMap.put(Events.DURATION, "duration");
        sEventsProjectionMap.put(Events.ALL_DAY, "allDay");
        sEventsProjectionMap.put(Events.VISIBILITY, "visibility");
        sEventsProjectionMap.put(Events.TRANSPARENCY, "transparency");
        sEventsProjectionMap.put(Events.HAS_ALARM, "hasAlarm");
        sEventsProjectionMap.put(Events.HAS_EXTENDED_PROPERTIES, "hasExtendedProperties");
        sEventsProjectionMap.put(Events.RRULE, "rrule");
        sEventsProjectionMap.put(Events.RDATE, "rdate");
        sEventsProjectionMap.put(Events.EXRULE, "exrule");
        sEventsProjectionMap.put(Events.EXDATE, "exdate");
        sEventsProjectionMap.put(Events.ORIGINAL_EVENT, "originalEvent");
        sEventsProjectionMap.put(Events.ORIGINAL_INSTANCE_TIME, "originalInstanceTime");
        sEventsProjectionMap.put(Events.ORIGINAL_ALL_DAY, "originalAllDay");
        sEventsProjectionMap.put(Events.LAST_DATE, "lastDate");
        sEventsProjectionMap.put(Events.HAS_ATTENDEE_DATA, "hasAttendeeData");
        sEventsProjectionMap.put(Events.CALENDAR_ID, "calendar_id");
        sEventsProjectionMap.put(Events.GUESTS_CAN_INVITE_OTHERS, "guestsCanInviteOthers");
        sEventsProjectionMap.put(Events.GUESTS_CAN_MODIFY, "guestsCanModify");
        sEventsProjectionMap.put(Events.GUESTS_CAN_SEE_GUESTS, "guestsCanSeeGuests");
        sEventsProjectionMap.put(Events.ORGANIZER, "organizer");

        // Calendar columns
        sEventsProjectionMap.put(Events.COLOR, "color");
        sEventsProjectionMap.put(Events.ACCESS_LEVEL, "access_level");
        sEventsProjectionMap.put(Events.SELECTED, "selected");
        sEventsProjectionMap.put(Calendars.URL, "url");
        sEventsProjectionMap.put(Calendars.TIMEZONE, "timezone");
        sEventsProjectionMap.put(Calendars.OWNER_ACCOUNT, "ownerAccount");

        // Put the shared items into the Instances projection map
        sInstancesProjectionMap = new HashMap<String, String>(sEventsProjectionMap);
        sAttendeesProjectionMap = new HashMap<String, String>(sEventsProjectionMap);
        sRemindersProjectionMap = new HashMap<String, String>(sEventsProjectionMap);
        sCalendarAlertsProjectionMap = new HashMap<String, String>(sEventsProjectionMap);

        sEventsProjectionMap.put(Events._ID, "Events._id AS _id");
        sEventsProjectionMap.put(Events._SYNC_ID, "Events._sync_id AS _sync_id");
        sEventsProjectionMap.put(Events._SYNC_VERSION, "Events._sync_version AS _sync_version");
        sEventsProjectionMap.put(Events._SYNC_TIME, "Events._sync_time AS _sync_time");
        sEventsProjectionMap.put(Events._SYNC_LOCAL_ID, "Events._sync_local_id AS _sync_local_id");
        sEventsProjectionMap.put(Events._SYNC_DIRTY, "Events._sync_dirty AS _sync_dirty");
        sEventsProjectionMap.put(Events._SYNC_ACCOUNT, "Events._sync_account AS _sync_account");
        sEventsProjectionMap.put(Events._SYNC_ACCOUNT_TYPE,
                "Events._sync_account_type AS _sync_account_type");

        // Instances columns
        sInstancesProjectionMap.put(Instances.BEGIN, "begin");
        sInstancesProjectionMap.put(Instances.END, "end");
        sInstancesProjectionMap.put(Instances.EVENT_ID, "Instances.event_id AS event_id");
        sInstancesProjectionMap.put(Instances._ID, "Instances._id AS _id");
        sInstancesProjectionMap.put(Instances.START_DAY, "startDay");
        sInstancesProjectionMap.put(Instances.END_DAY, "endDay");
        sInstancesProjectionMap.put(Instances.START_MINUTE, "startMinute");
        sInstancesProjectionMap.put(Instances.END_MINUTE, "endMinute");

        // Attendees columns
        sAttendeesProjectionMap.put(Attendees.EVENT_ID, "event_id");
        sAttendeesProjectionMap.put(Attendees._ID, "Attendees._id AS _id");
        sAttendeesProjectionMap.put(Attendees.ATTENDEE_NAME, "attendeeName");
        sAttendeesProjectionMap.put(Attendees.ATTENDEE_EMAIL, "attendeeEmail");
        sAttendeesProjectionMap.put(Attendees.ATTENDEE_STATUS, "attendeeStatus");
        sAttendeesProjectionMap.put(Attendees.ATTENDEE_RELATIONSHIP, "attendeeRelationship");
        sAttendeesProjectionMap.put(Attendees.ATTENDEE_TYPE, "attendeeType");

        // Reminders columns
        sRemindersProjectionMap.put(Reminders.EVENT_ID, "event_id");
        sRemindersProjectionMap.put(Reminders._ID, "Reminders._id AS _id");
        sRemindersProjectionMap.put(Reminders.MINUTES, "minutes");
        sRemindersProjectionMap.put(Reminders.METHOD, "method");

        // CalendarAlerts columns
        sCalendarAlertsProjectionMap.put(CalendarAlerts.EVENT_ID, "event_id");
        sCalendarAlertsProjectionMap.put(CalendarAlerts._ID, "CalendarAlerts._id AS _id");
        sCalendarAlertsProjectionMap.put(CalendarAlerts.BEGIN, "begin");
        sCalendarAlertsProjectionMap.put(CalendarAlerts.END, "end");
        sCalendarAlertsProjectionMap.put(CalendarAlerts.ALARM_TIME, "alarmTime");
        sCalendarAlertsProjectionMap.put(CalendarAlerts.STATE, "state");
        sCalendarAlertsProjectionMap.put(CalendarAlerts.MINUTES, "minutes");
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
        }
    }
}
