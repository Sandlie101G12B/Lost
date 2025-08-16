package code.name.monkey.lost.repository;

import android.database.AbstractCursor;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This cursor basically wraps a song cursor and is given a list of the order of the ids of the
 * contents of the cursor. It wraps the Cursor and simulates the internal cursor being sorted by
 * moving the point to the appropriate spot
 */
public class SortedCursor extends AbstractCursor {
  // cursor to wrap
  private final Cursor mCursor;
  // the map of external indices to internal indices
  private ArrayList<Integer> mOrderedPositions;

    /**
   * @param cursor to wrap
   * @param order the list of unique ids in sorted order to display
   * @param columnName the column name of the id to look up in the internal cursor
   */
  public SortedCursor(
      @NonNull final Cursor cursor, @Nullable final String[] order, final String columnName) {
    mCursor = cursor;
      // this contains the ids that weren't found in the underlying cursor
      ArrayList<String> mMissingValues = buildCursorPositionMapping(order, columnName);
  }

  @NonNull
  private ArrayList<String> buildCursorPositionMapping(
      @Nullable final String[] order, final String columnName) {
    ArrayList<String> missingValues = new ArrayList<>();

    mOrderedPositions = new ArrayList<>(mCursor.getCount());

      // this contains the mapped cursor positions and afterwards the extra ids that weren't found
      HashMap<String, Integer> mMapCursorPositions = new HashMap<>(mCursor.getCount());
    final int valueColumnIndex = mCursor.getColumnIndex(columnName);

    if (mCursor.moveToFirst()) {
      // first figure out where each of the ids are in the cursor
      do {
        mMapCursorPositions.put(mCursor.getString(valueColumnIndex), mCursor.getPosition());
      } while (mCursor.moveToNext());

      if (order != null) {
        for (final String value : order) {
          if (mMapCursorPositions.containsKey(value)) {
            mOrderedPositions.add(mMapCursorPositions.get(value));
            mMapCursorPositions.remove(value);
          } else {
            missingValues.add(value);
          }
        }
      }

      mCursor.moveToFirst();
    }

    return missingValues;
  }


  @Override
  public void close() {
    mCursor.close();

    super.close();
  }

  @Override
  public int getCount() {
    return mOrderedPositions.size();
  }

  @Override
  public String[] getColumnNames() {
    return mCursor.getColumnNames();
  }

  @Override
  public String getString(int column) {
    return mCursor.getString(column);
  }

  @Override
  public short getShort(int column) {
    return mCursor.getShort(column);
  }

  @Override
  public int getInt(int column) {
    return mCursor.getInt(column);
  }

  @Override
  public long getLong(int column) {
    return mCursor.getLong(column);
  }

  @Override
  public float getFloat(int column) {
    return mCursor.getFloat(column);
  }

  @Override
  public double getDouble(int column) {
    return mCursor.getDouble(column);
  }

  @Override
  public boolean isNull(int column) {
    return mCursor.isNull(column);
  }

  @Override
  public boolean onMove(int oldPosition, int newPosition) {
    if (newPosition >= 0 && newPosition < getCount()) {
      mCursor.moveToPosition(mOrderedPositions.get(newPosition));
      return true;
    }
    return false;
  }
}
