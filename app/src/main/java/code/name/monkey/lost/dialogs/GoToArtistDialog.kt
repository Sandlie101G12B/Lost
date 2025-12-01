package code.name.monkey.lost.dialogs

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.navigation.findNavController
import code.name.monkey.lost.EXTRA_ARTIST_ID
import code.name.monkey.lost.R
import code.name.monkey.lost.activities.MainActivity
import code.name.monkey.lost.extensions.currentFragment
import code.name.monkey.lost.model.Song
import com.google.android.material.bottomsheet.BottomSheetBehavior

class GoToArtistDialog : DialogFragment() {

    companion object {
        private const val ARG_ARTIST_NAMES = "artist_names"
        private const val ARG_ARTIST_IDS = "artist_ids"

        fun newInstance(song: Song): GoToArtistDialog {
            val dialog = GoToArtistDialog()
            val args = Bundle()
            args.putStringArrayList(ARG_ARTIST_NAMES, ArrayList(song.artistNames))
            args.putIntegerArrayList(ARG_ARTIST_IDS, ArrayList(song.artistIds.map { it.toInt() }))
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val artistNames = requireArguments().getStringArrayList(ARG_ARTIST_NAMES) ?: arrayListOf()
        val artistIds = requireArguments().getIntegerArrayList(ARG_ARTIST_IDS) ?: arrayListOf()

        val adapter = ArrayAdapter(requireContext(), android.R.layout.select_dialog_item, artistNames)

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_go_to_artist)
            .setAdapter(adapter) { _, which ->
                val artistId = artistIds[which].toLong()
                goToArtist(requireActivity(), artistId)
            }
            .create()
    }

    private fun goToArtist(activity: Activity, artistId: Long) {
        if (activity !is MainActivity) return
        activity.apply {
            // Remove exit transition of current fragment
            currentFragment(R.id.fragment_container)?.exitTransition = null

            // Hide Bottom Bar First
            setBottomNavVisibility(false)
            if (getBottomSheetBehavior().state == BottomSheetBehavior.STATE_EXPANDED) {
                collapsePanel()
            }

            findNavController(R.id.fragment_container).navigate(
                R.id.artistDetailsFragment,
                bundleOf(EXTRA_ARTIST_ID to artistId)
            )
        }
    }
}
