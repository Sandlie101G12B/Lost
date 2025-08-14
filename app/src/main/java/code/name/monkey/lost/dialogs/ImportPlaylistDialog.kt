package code.name.monkey.lost.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import code.name.monkey.lost.R
import code.name.monkey.lost.extensions.colorButtons
import code.name.monkey.lost.extensions.materialDialog
import code.name.monkey.lost.fragments.LibraryViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ImportPlaylistDialog : DialogFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return materialDialog(R.string.import_playlist)
            .setMessage(R.string.import_playlist_message)
            .setPositiveButton(R.string.import_label) { _, _ ->
                try {
                    libraryViewModel.importPlaylists()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .create()
            .colorButtons()
    }
}
