package code.name.monkey.appthemehelper.common.prefs.supportv7;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import code.name.monkey.appthemehelper.common.prefs.supportv7.dialogs.ATEListPreferenceDialogFragmentCompat;
import code.name.monkey.appthemehelper.common.prefs.supportv7.dialogs.ATEPreferenceDialogFragment;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public abstract class ATEPreferenceFragmentCompat extends PreferenceFragmentCompat {
    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        // Replaced getCallbackFragment() with getTargetFragment() to resolve lint warning
        if (getTargetFragment() instanceof OnPreferenceDisplayDialogCallback) {
            ((OnPreferenceDisplayDialogCallback) getTargetFragment()).onPreferenceDisplayDialog(this, preference);
            return;
        }

        if (this.getActivity() instanceof OnPreferenceDisplayDialogCallback) {
            ((OnPreferenceDisplayDialogCallback) this.getActivity()).onPreferenceDisplayDialog(this, preference);
            return;
        }

        // Use getParentFragmentManager() instead of getFragmentManager()
        if (getParentFragmentManager().findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") == null) {
            DialogFragment dialogFragment = onCreatePreferenceDialog(preference);

            if (dialogFragment != null) {
                // TODO: Consider migrating from setTargetFragment to the Fragment Result API
                dialogFragment.setTargetFragment(this, 0);
                // Use getParentFragmentManager() instead of getFragmentManager()
                dialogFragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
                return;
            }
        }

        super.onDisplayPreferenceDialog(preference);
    }

    @Nullable
    public DialogFragment onCreatePreferenceDialog(Preference preference) {
        if (preference instanceof ATEListPreference) {
            return ATEListPreferenceDialogFragmentCompat.newInstance(preference.getKey());
        } else if (preference instanceof ATEDialogPreference) {
            return ATEPreferenceDialogFragment.newInstance(preference.getKey());
        }
        return null;
    }
}
