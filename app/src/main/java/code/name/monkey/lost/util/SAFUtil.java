package code.name.monkey.lost.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.generic.Utils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import code.name.monkey.lost.R;
import code.name.monkey.lost.model.Song;
import timber.log.Timber;

public class SAFUtil {

  public static final String TAG = SAFUtil.class.getSimpleName();

  public static boolean isSAFRequired(File file) {
    return !file.canWrite();
  }

  public static boolean isSAFRequired(String path) {
    return isSAFRequired(new File(path));
  }

  public static boolean isSAFRequired(AudioFile audio) {
    return isSAFRequired(audio.getFile());
  }

  public static boolean isSAFRequired(Song song) {
    return isSAFRequired(song.getData());
  }

  public static boolean isSAFRequiredForSongs(List<Song> songs) {
    for (Song song : songs) {
      if (isSAFRequired(song)) return true;
    }
    return false;
  }

  public static void saveTreeUri(Context context, Intent data) {
    Uri uri = data.getData();
      assert uri != null;
      context
        .getContentResolver()
        .takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    PreferenceUtil.INSTANCE.setSafSdCardUri(uri.toString());
  }

  public static boolean isTreeUriSaved(Context context) {
      return !TextUtils.isEmpty(PreferenceUtil.INSTANCE.getSafSdCardUri());
  }

  public static boolean isSDCardAccessGranted(Context context) {
    if (!isTreeUriSaved(context)) return false;

    String sdcardUri = PreferenceUtil.INSTANCE.getSafSdCardUri();

    List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
    for (UriPermission perm : perms) {
      if (perm.getUri().toString().equals(sdcardUri) && perm.isWritePermission()) return true;
    }

    return false;
  }
  
  @Nullable
  public static Uri findDocument(DocumentFile dir, List<String> segments) {
    for (DocumentFile file : dir.listFiles()) {
      int index = segments.indexOf(file.getName());
      if (index == -1) {
        continue;
      }

      if (file.isDirectory()) {
        segments.remove(file.getName());
        return findDocument(file, segments);
      }

      if (file.isFile() && index == segments.size() - 1) {
        // got to the last part
        return file.getUri();
      }
    }

    return null;
  }

  public static void write(Context context, AudioFile audio, Uri safUri) {
    if (isSAFRequired(audio)) {
      writeSAF(context, audio, safUri);
    } else {
      try {
          writeFile(audio);
      } catch (CannotWriteException e) {
        Timber.tag(TAG).e(e, "Error writing file");
      }
    }
  }

  public static void writeFile(AudioFile audio) throws CannotWriteException {
    audio.commit();
  }

  public static void writeSAF(Context context, AudioFile audio, Uri safUri) {
      Uri uri = null;

    if (context == null) {
      Timber.tag(TAG).e("writeSAF: context == null");
      return;
    }

    if (isTreeUriSaved(context)) {
      List<String> pathSegments =
          new ArrayList<>(Arrays.asList(audio.getFile().getAbsolutePath().split("/")));
      Uri sdcard = Uri.parse(PreferenceUtil.INSTANCE.getSafSdCardUri());
      uri = findDocument(Objects.requireNonNull(DocumentFile.fromTreeUri(context, sdcard)), pathSegments);
    }

    if (uri == null) {
      uri = safUri;
    }

    if (uri == null) {
      Timber.tag(TAG).e("writeSAF: Can't get SAF URI");
        toast(context, context.getString(R.string.saf_error_uri));
      return;
    }

    try {
      // copy file to app folder to use jaudiotagger
      final File original = audio.getFile();
      File temp = File.createTempFile("tmp-media", '.' + Utils.getExtension(original));
      Utils.copy(original, temp);
      temp.deleteOnExit();
      audio.setFile(temp);
      writeFile(audio);

      ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "rw");
      if (pfd == null) {
        Timber.tag(TAG).e("writeSAF: SAF provided incorrect URI: %s", uri);
        return;
      }

      // now read persisted data and write it to real FD provided by SAF
      FileInputStream fis = new FileInputStream(temp);
      byte[] audioContent = FileUtil.readBytes(fis);
      FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
        fos.write(audioContent);
        fos.close();

        temp.delete();
    } catch (final Exception e) {
      Timber.tag(TAG).e(e, "writeSAF: Failed to write to file descriptor provided by SAF");

      toast(
          context,
          String.format(context.getString(R.string.saf_write_failed), e.getLocalizedMessage()));
    }
  }

  public static void delete(Context context, String path, Uri safUri) {
    if (isSAFRequired(path)) {
      deleteSAF(context, path, safUri);
    } else {
      try {
        deleteFile(path);
      } catch (NullPointerException e) {
          Timber.tag("MusicUtils").e("Failed to find file %s", path);
      } catch (Exception e) {
          Timber.tag(TAG).e("Error deleting file $e");
      }
    }
  }

  public static void deleteFile(String path) {
    File fileToDelete = new File(path);
    if (!fileToDelete.delete()) {
        Timber.tag(TAG).w("Failed to delete file: %s", path);
    }
  }

  public static void deleteSAF(Context context, String path, Uri safUri) {
    Uri uri = null;

      if (context == null) {
          Timber.tag(TAG).e("deleteSAF: context == null");
      return;
    }

    if (isTreeUriSaved(context)) {
      List<String> pathSegments = new ArrayList<>(Arrays.asList(path.split("/")));
      Uri sdcard = Uri.parse(PreferenceUtil.INSTANCE.getSafSdCardUri());
      uri = findDocument(Objects.requireNonNull(DocumentFile.fromTreeUri(context, sdcard)), pathSegments);
    }

    if (uri == null) {
        uri = safUri;
    }

      if (uri == null) {
          Timber.tag(TAG).e("deleteSAF: Can't get SAF URI");
      toast(context, context.getString(R.string.saf_error_uri));
      return;
    }

    try {
      DocumentsContract.deleteDocument(context.getContentResolver(), uri);
    } catch (final Exception e) {
      Timber.tag(TAG).e(e, "deleteSAF: Failed to delete a file descriptor provided by SAF");

      toast(
          context,
          String.format(context.getString(R.string.saf_delete_failed), e.getLocalizedMessage()));
    }
  }

  private static void toast(final Context context, final String message) {
    if (context instanceof Activity) {
      ((Activity) context)
          .runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
  }
}
