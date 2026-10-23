package org.briarproject.briar.android.family;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.briarproject.briar.R;

import dev.gagan.family.update.FamilyUpdater;

/**
 * Settings entry for the family update channel. Thin by design: all the logic lives in
 * dev.gagan.family.update.FamilyUpdater, the same file the bitchat fork uses.
 */
public class FamilyUpdateFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.family_update_prefs, rootKey);
        Preference check = findPreference("pref_family_check");
        if (check == null) return;
        check.setOnPreferenceClickListener(preference -> {
            FamilyUpdater.checkNow(result -> {
                if (!isAdded() || getActivity() == null) return;
                if (result.state == FamilyUpdater.Result.State.AVAILABLE) {
                    FamilyUpdater.showDialog(getActivity(), null);
                } else {
                    Toast.makeText(getContext(), result.message, Toast.LENGTH_LONG).show();
                }
            });
            return true;
        });
    }
}
