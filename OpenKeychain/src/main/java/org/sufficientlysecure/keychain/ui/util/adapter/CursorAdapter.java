package org.sufficientlysecure.keychain.ui.util.adapter;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.DataSetObserver;
import android.os.Handler;
import android.support.v7.widget.RecyclerView;

import org.openintents.openpgp.util.OpenPgpUtils;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

public abstract class CursorAdapter<C extends CursorAdapter.AbstractCursor, VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {
    public static final String TAG = "CursorAdapter";

    private C mCursor;
    private Context mContext;
    private boolean mDataValid;

    private ChangeObserver mChangeObserver;
    private DataSetObserver mDataSetObserver;

    /**
     * If set the adapter will register a content observer on the cursor and will call
     * {@link #onContentChanged()} when a notification comes in.  Be careful when
     * using this flag: you will need to unset the current Cursor from the adapter
     * to avoid leaks due to its registered observers.  This flag is not needed
     * when using a CursorAdapter with a
     * {@link android.content.CursorLoader}.
     */
    public static final int FLAG_REGISTER_CONTENT_OBSERVER = 0x02;

    /**
     * Constructor that allows control over auto-requery.  It is recommended
     * you not use this, but instead {@link #CursorAdapter(Context, AbstractCursor, int)}.
     * When using this constructor, {@link #FLAG_REGISTER_CONTENT_OBSERVER}
     * will always be set.
     *
     * @param c The cursor from which to get the data.
     * @param context The context
     */
    public CursorAdapter(Context context, C c) {
        setHasStableIds(true);
        init(context, c, FLAG_REGISTER_CONTENT_OBSERVER);
    }

    /**
     * Recommended constructor.
     *
     * @param c The cursor from which to get the data.
     * @param context The context
     * @param flags Flags used to determine the behavior of the adapter
     * @see #FLAG_REGISTER_CONTENT_OBSERVER
     */
    public CursorAdapter(Context context, C c, int flags) {
        setHasStableIds(true);
        init(context, c, flags);
    }

    private void init(Context context, C c, int flags) {
        boolean cursorPresent = c != null;
        mCursor = c;
        mDataValid = cursorPresent;
        mContext = context;
        if ((flags & FLAG_REGISTER_CONTENT_OBSERVER) == FLAG_REGISTER_CONTENT_OBSERVER) {
            mChangeObserver = new ChangeObserver();
            mDataSetObserver = new MyDataSetObserver();
        } else {
            mChangeObserver = null;
            mDataSetObserver = null;
        }

        if (cursorPresent) {
            if (mChangeObserver != null) c.registerContentObserver(mChangeObserver);
            if (mDataSetObserver != null) c.registerDataSetObserver(mDataSetObserver);
        }

        setHasStableIds(true);
    }

    /**
     * Returns the cursor.
     * @return the cursor.
     */
    public C getCursor() {
        return mCursor;
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * @see android.support.v7.widget.RecyclerView.Adapter#getItemCount()
     */
    @Override
    public int getItemCount() {
        if (mDataValid && mCursor != null) {
            return mCursor.getCount();
        } else {
            return 0;
        }
    }

    public boolean hasValidData() {
        mDataValid = hasOpenCursor();
        return mDataValid;
    }

    private boolean hasOpenCursor() {
        Cursor cursor = getCursor();
        if (cursor != null && cursor.isClosed()) {
            swapCursor(null);
            return false;
        }

        return cursor != null;
    }

    /**
     * @see android.support.v7.widget.RecyclerView.Adapter#getItemId(int)
     *
     * @param position Adapter position to query
     * @return the id of the item
     */
    @Override
    public long getItemId(int position) {
        if (mDataValid && mCursor != null) {
            if (moveCursor(position)) {
                return getIdFromCursor(mCursor);
            } else {
                return RecyclerView.NO_ID;
            }
        } else {
            return RecyclerView.NO_ID;
        }
    }

    /**
     * Return the id of the item represented by the row the cursor
     * is currently moved to.
     * @param cursor The cursor moved to the correct position.
     * @return The id of the dataset
     */
    public long getIdFromCursor(C cursor) {
        if(cursor != null) {
            return cursor.getEntryId();
        } else {
            return RecyclerView.NO_ID;
        }
    }

    public void moveCursorOrThrow(int position)
            throws IndexOutOfBoundsException, IllegalStateException {

        if(position >= getItemCount() || position < -1) {
            throw new IndexOutOfBoundsException("Position: " + position
                    + " is invalid for this data set!");
        }

        if(!mDataValid) {
            throw new IllegalStateException("Attempt to move cursor over invalid data set!");
        }

        if(!mCursor.moveToPosition(position)) {
            throw new IllegalStateException("Couldn't move cursor from position: "
                    + mCursor.getPosition() + " to position: " + position + "!");
        }
    }

    public boolean moveCursor(int position) {
        if(position >= getItemCount() || position < -1) {
            Log.w(TAG, "Position: %d is invalid for this data set!");
            return false;
        }

        if(!mDataValid) {
            Log.d(TAG, "Attempt to move cursor over invalid data set!");
        }

        return mCursor.moveToPosition(position);
    }

    /**
     * Change the underlying cursor to a new cursor. If there is an existing cursor it will be
     * closed.
     *
     * @param cursor The new cursor to be used
     */
    public void changeCursor(C cursor) {
        Cursor old = swapCursor(cursor);
        if (old != null) {
            old.close();
        }
    }

    /**
     * Swap in a new Cursor, returning the old Cursor.  Unlike
     * {@link #changeCursor(AbstractCursor)}, the returned old Cursor is <em>not</em>
     * closed.
     *
     * @param newCursor The new cursor to be used.
     * @return Returns the previously set Cursor, or null if there wasa not one.
     * If the given new Cursor is the same instance is the previously set
     * Cursor, null is also returned.
     */
    public C swapCursor(C newCursor) {
        if (newCursor == mCursor) {
            return null;
        }

        C oldCursor = mCursor;
        if (oldCursor != null) {
            if (mChangeObserver != null) oldCursor.unregisterContentObserver(mChangeObserver);
            if (mDataSetObserver != null) oldCursor.unregisterDataSetObserver(mDataSetObserver);
        }

        mCursor = newCursor;
        if (newCursor != null) {
            if (mChangeObserver != null) newCursor.registerContentObserver(mChangeObserver);
            if (mDataSetObserver != null) newCursor.registerDataSetObserver(mDataSetObserver);
            mDataValid = true;
            // notify the observers about the new cursor
            onContentChanged();
        } else {
            mDataValid = false;
            // notify the observers about the lack of a data set
            onContentChanged();
        }

        return oldCursor;
    }

    /**
     * <p>Converts the cursor into a CharSequence. Subclasses should override this
     * method to convert their results. The default implementation returns an
     * empty String for null values or the default String representation of
     * the value.</p>
     *
     * @param cursor the cursor to convert to a CharSequence
     * @return a CharSequence representing the value
     */
    public CharSequence convertToString(Cursor cursor) {
        return cursor == null ? "" : cursor.toString();
    }

    /**
     * Called when the {@link ContentObserver} on the cursor receives a change notification.
     * The default implementation provides the auto-requery logic, but may be overridden by
     * sub classes.
     *
     * @see ContentObserver#onChange(boolean)
     */
    protected void onContentChanged() {
        notifyDataSetChanged();
    }

    private class ChangeObserver extends ContentObserver {
        public ChangeObserver() {
            super(new Handler());
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            onContentChanged();
        }
    }

    private class MyDataSetObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            mDataValid = true;
            onContentChanged();
        }

        @Override
        public void onInvalidated() {
            mDataValid = false;
            onContentChanged();
        }
    }

    public static abstract class AbstractCursor extends CursorWrapper {
        public static final String[] PROJECTION = { "_id" };

        public static <T extends AbstractCursor> T wrap(Cursor cursor, Class<T> type) {
            if (cursor != null) {
                try {
                    Constructor<T> constructor = type.getConstructor(Cursor.class);
                    return constructor.newInstance(cursor);
                } catch (Exception e) {
                    Log.e(Constants.TAG, "Could not create instance of cursor wrapper!", e);
                }
            }

            return null;
        }

        private HashMap<String, Integer> mColumnIndices;

        /**
         * Creates a cursor wrapper.
         *
         * @param cursor The underlying cursor to wrap.
         */
        protected AbstractCursor(Cursor cursor) {
            super(cursor);
            mColumnIndices = new HashMap<>(cursor.getColumnCount() * 4 / 3, 0.75f);
        }

        @Override
        public void close() {
            mColumnIndices.clear();
            super.close();
        }

        public final int getEntryId() {
            int index = getColumnIndexOrThrow("_id");
            return getInt(index);
        }

        @Override
        public final int getColumnIndexOrThrow(String colName) {
            Integer colIndex = mColumnIndices.get(colName);
            if(colIndex == null) {
                colIndex = super.getColumnIndexOrThrow(colName);
                mColumnIndices.put(colName, colIndex);
            } else if (colIndex < 0){
                throw new IllegalArgumentException("Could not get column index for name: \"" + colName + "\"");
            }

            return colIndex;
        }

        @Override
        public final int getColumnIndex(String colName) {
            Integer colIndex = mColumnIndices.get(colName);
            if(colIndex == null) {
                colIndex = super.getColumnIndex(colName);
                mColumnIndices.put(colName, colIndex);
            }

            return colIndex;
        }
    }

    public static class KeyCursor extends AbstractCursor {
        public static final String[] PROJECTION;

        static {
            ArrayList<String> arr = new ArrayList<>();
            arr.addAll(Arrays.asList(AbstractCursor.PROJECTION));
            arr.addAll(Arrays.asList(
                    KeychainContract.KeyRings.MASTER_KEY_ID,
                    KeychainContract.KeyRings.USER_ID,
                    KeychainContract.KeyRings.IS_REVOKED,
                    KeychainContract.KeyRings.IS_EXPIRED,
                    KeychainContract.KeyRings.HAS_DUPLICATE_USER_ID,
                    KeychainContract.KeyRings.CREATION
            ));

            PROJECTION = arr.toArray(new String[arr.size()]);
        }

        public static KeyCursor wrap(Cursor cursor) {
            if (cursor != null) {
                return new KeyCursor(cursor);
            } else {
                return null;
            }
        }

        /**
         * Creates a cursor wrapper.
         *
         * @param cursor The underlying cursor to wrap.
         */
        protected KeyCursor(Cursor cursor) {
            super(cursor);
        }

        public long getKeyId() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.MASTER_KEY_ID);
            return getLong(index);
        }

        public String getRawUserId() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.USER_ID);
            return getString(index);
        }

        public OpenPgpUtils.UserId getUserId() {
            return KeyRing.splitUserId(getRawUserId());
        }

        public boolean hasDuplicate() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.HAS_DUPLICATE_USER_ID);
            return getLong(index) > 0L;
        }

        public boolean isRevoked() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.IS_REVOKED);
            return getInt(index) > 0;
        }

        public boolean isExpired() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.IS_EXPIRED);
            return getInt(index) > 0;
        }

        public long getCreationTime() {
            int index = getColumnIndexOrThrow(KeychainContract.KeyRings.CREATION);
            return getLong(index) * 1000;
        }

        public Date getCreationDate() {
            return new Date(getCreationTime());
        }
    }
}