/* Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.example.games.catt2;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.RatingBar;
import android.widget.RatingBar.OnRatingBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Result;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.games.AnnotatedData;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.GamesClientStatusCodes;
import com.google.android.gms.games.SnapshotsClient;
import com.google.android.gms.games.snapshot.Snapshot;
import com.google.android.gms.games.snapshot.SnapshotMetadata;
import com.google.android.gms.games.snapshot.SnapshotMetadataBuffer;
import com.google.android.gms.games.snapshot.SnapshotMetadataChange;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;

/**
 * Collect All the Stars sample. This sample demonstrates how to use the cloud save features
 * of the Google Play game services API. It's a "game" where there are several worlds
 * with several levels each, and on each level the player can get from 0 to 5 stars.
 * The progress of the player is saved to the cloud and kept in sync across all their devices.
 * If they earn 5 stars on level 1 on one device and then earn 4 stars on level 2 on a different
 * device, upon synchronizing the consolidated progress will be 5 stars on level 1 AND
 * 4 stars on level 2. If they clear the same level on two different devices, then the biggest
 * star rating of the two will apply.
 * <p>
 * It's worth noting that this sample also works offline, and even when the player is not
 * signed in. In all cases, the progress is saved locally as well.
 *
 * @author Bruno Oliveira (Google)
 */
public class MainActivity extends AppCompatActivity implements
    View.OnClickListener,
    OnRatingBarChangeListener {

  private static final String TAG = "CollectAllTheStars2";

  // Request code used to invoke sign in user interactions.
  private static final int RC_SIGN_IN = 9001;

  // Request code for listing saved games
  private static final int RC_LIST_SAVED_GAMES = 9002;

  // Request code for selecting a snapshot
  private static final int RC_SELECT_SNAPSHOT = 9003;

  // Request code for saving the game to a snapshot.
  private static final int RC_SAVE_SNAPSHOT = 9004;

  private static final int RC_LOAD_SNAPSHOT = 9005;

  // Client used to sign in with Google APIs
  private GoogleSignInClient mGoogleSignInClient;

  // Client used to interact with Google Snapshots.
  private SnapshotsClient mSnapshotsClient = null;

  // current save game - serializable to and from the saved game
  SaveGame mSaveGame = new SaveGame();

  private String currentSaveName = "snapshotTemp";

  // world we're currently viewing
  int mWorld = 1;
  private static final int WORLD_MIN = 1;
  private static final int WORLD_MAX = 20;
  private static final int LEVELS_PER_WORLD = 12;

  // level we're currently "playing"
  int mLevel = 0;

  // state of "playing" - used to make the back button work correctly
  boolean mInLevel = false;

  // progress dialog we display while we're loading state from the cloud
  ProgressDialog mLoadingDialog = null;

  // the level buttons (the ones the user clicks to play a given level)
  final static int[] LEVEL_BUTTON_IDS = {
      R.id.button_level_1, R.id.button_level_2, R.id.button_level_3, R.id.button_level_4,
      R.id.button_level_5, R.id.button_level_6, R.id.button_level_7, R.id.button_level_8,
      R.id.button_level_9, R.id.button_level_10, R.id.button_level_11, R.id.button_level_12
  };

  // star strings (we use the Unicode BLACK STAR and WHITE STAR characters -- lazy graphics!)
  final static String[] STAR_STRINGS = {
      "\u2606\u2606\u2606\u2606\u2606", // 0 stars
      "\u2605\u2606\u2606\u2606\u2606", // 1 star
      "\u2605\u2605\u2606\u2606\u2606", // 2 stars
      "\u2605\u2605\u2605\u2606\u2606", // 3 stars
      "\u2605\u2605\u2605\u2605\u2606", // 4 stars
      "\u2605\u2605\u2605\u2605\u2605", // 5 stars
  };

  // Members related to the conflict resolution chooser of Snapshots.
  final static int MAX_SNAPSHOT_RESOLVE_RETRIES = 50;

  /**
   * Start a sign in activity.  To properly handle the result, call tryHandleSignInResult from
   * your Activity's onActivityResult function
   */
  public void startSignInIntent() {
    startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN);
  }

  /**
   * Try to sign in without displaying dialogs to the user.
   * <p>
   * If the user has already signed in previously, it will not show dialog.
   */
  public void signInSilently() {
    Log.d(TAG, "signInSilently()");

    mGoogleSignInClient.silentSignIn().addOnCompleteListener(this,
        new OnCompleteListener<GoogleSignInAccount>() {
          @Override
          public void onComplete(@NonNull Task<GoogleSignInAccount> task) {
            if (task.isSuccessful()) {
              Log.d(TAG, "signInSilently(): success");
              onConnected(task.getResult());
            } else {
              Log.d(TAG, "signInSilently(): failure", task.getException());
              onDisconnected();
            }
          }
        });
  }

  public void signOut() {
    Log.d(TAG, "signOut()");

    mGoogleSignInClient.signOut().addOnCompleteListener(this,
        new OnCompleteListener<Void>() {
          @Override
          public void onComplete(@NonNull Task<Void> task) {

            if (task.isSuccessful()) {
              Log.d(TAG, "signOut(): success");
            } else {
              handleException(task.getException(), "signOut() failed!");
            }

            onDisconnected();
          }
        });
  }


  /**
   * You can capture the Snapshot selection intent in the onActivityResult method. The result
   * either indicates a new Snapshot was created (EXTRA_SNAPSHOT_NEW) or was selected.
   */
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent intent) {

    if (requestCode == RC_SIGN_IN) {

      Task<GoogleSignInAccount> task =
          GoogleSignIn.getSignedInAccountFromIntent(intent);

      try {
        GoogleSignInAccount account = task.getResult(ApiException.class);
        onConnected(account);
      } catch (ApiException apiException) {
        String message = apiException.getMessage();
        if (message == null || message.isEmpty()) {
          message = getString(R.string.signin_other_error);
        }

        onDisconnected();

        new AlertDialog.Builder(this)
            .setMessage(message)
            .setNeutralButton(android.R.string.ok, null)
            .show();
      }
    } else if (requestCode == RC_LIST_SAVED_GAMES) {
      // the standard snapshot selection intent
      if (intent != null) {
        if (intent.hasExtra(SnapshotsClient.EXTRA_SNAPSHOT_METADATA)) {
          // Load a snapshot.
          SnapshotMetadata snapshotMetadata =
              intent.getParcelableExtra(SnapshotsClient.EXTRA_SNAPSHOT_METADATA);
          currentSaveName = snapshotMetadata.getUniqueName();
          loadFromSnapshot(snapshotMetadata);
        } else if (intent.hasExtra(SnapshotsClient.EXTRA_SNAPSHOT_NEW)) {
          // Create a new snapshot named with a unique string
          String unique = Long.toString(System.currentTimeMillis());
          currentSaveName = "snapshotTemp-" + unique;
          saveSnapshot(null);
        }
      }
    }
    // the example use of Snapshot.load() which displays a custom list of snapshots.
    else if (requestCode == RC_SELECT_SNAPSHOT) {
      Log.d(TAG, "Selected a snapshot!");
      if (resultCode == RESULT_OK) {
        if (intent != null && intent.hasExtra(SelectSnapshotActivity.SNAPSHOT_METADATA)) {
          // Load a snapshot.
          SnapshotMetadata snapshotMetadata =
              intent.getParcelableExtra(SelectSnapshotActivity.SNAPSHOT_METADATA);
          currentSaveName = snapshotMetadata.getUniqueName();
          Log.d(TAG, "ok - loading " + currentSaveName);
          loadFromSnapshot(snapshotMetadata);
        } else {
          Log.w(TAG, "Expected snapshot metadata but found none.");
        }
      }
    }
    // loading a snapshot into the game.
    else if (requestCode == RC_LOAD_SNAPSHOT) {
      Log.d(TAG, "Loading a snapshot resultCode = " + resultCode);
      if (resultCode == RESULT_OK) {
        if (intent != null && intent.hasExtra(SelectSnapshotActivity.SNAPSHOT_METADATA)) {
          // Load a snapshot.
          String conflictId = intent.getStringExtra(SelectSnapshotActivity.CONFLICT_ID);
          int retryCount = intent.getIntExtra(SelectSnapshotActivity.RETRY_COUNT,
              MAX_SNAPSHOT_RESOLVE_RETRIES);
          SnapshotMetadata snapshotMetadata =
              intent.getParcelableExtra(SelectSnapshotActivity.SNAPSHOT_METADATA);
          if (conflictId == null) {
            loadFromSnapshot(snapshotMetadata);
          } else {
            Log.d(TAG, "resolving " + snapshotMetadata);
            resolveSnapshotConflict(requestCode, conflictId, retryCount,
                snapshotMetadata);
          }
        }
      }

    }
    // saving the game into a snapshot.
    else if (requestCode == RC_SAVE_SNAPSHOT) {
      if (resultCode == RESULT_OK) {
        if (intent != null && intent.hasExtra(SelectSnapshotActivity.SNAPSHOT_METADATA)) {
          // Load a snapshot.
          String conflictId = intent.getStringExtra(SelectSnapshotActivity.CONFLICT_ID);
          int retryCount = intent.getIntExtra(SelectSnapshotActivity.RETRY_COUNT,
              MAX_SNAPSHOT_RESOLVE_RETRIES);
          SnapshotMetadata snapshotMetadata =
              intent.getParcelableExtra(SelectSnapshotActivity.SNAPSHOT_METADATA);
          if (conflictId == null) {
            saveSnapshot(snapshotMetadata);
          } else {
            Log.d(TAG, "resolving " + snapshotMetadata);
            resolveSnapshotConflict(requestCode, conflictId, retryCount, snapshotMetadata);
          }
        }
      }
    }
    super.onActivityResult(requestCode, resultCode, intent);
  }


  @Override
  protected void onCreate(Bundle savedInstanceState) {

    log("onCreate.");
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // Create the client used to sign in.
    mGoogleSignInClient = GoogleSignIn.getClient(this,
        new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_GAMES_SIGN_IN)
            // Since we are using SavedGames, we need to add the SCOPE_APPFOLDER to access Google Drive.
            .requestScopes(Drive.SCOPE_APPFOLDER)
            .build());

    for (int id : LEVEL_BUTTON_IDS) {
      findViewById(id).setOnClickListener(this);
    }
    findViewById(R.id.button_next_world).setOnClickListener(this);
    findViewById(R.id.button_prev_world).setOnClickListener(this);
    findViewById(R.id.button_sign_in).setOnClickListener(this);
    findViewById(R.id.button_sign_out).setOnClickListener(this);
    ((RatingBar) findViewById(R.id.gameplay_rating)).setOnRatingBarChangeListener(this);
    mSaveGame = new SaveGame();
    updateUi();
    checkPlaceholderIds();
  }

  // Check the sample to ensure all placeholder ids are are updated with real-world values.
  // This is strictly for the purpose of the samples; you don't need this in a production
  // application.
  private void checkPlaceholderIds() {
    StringBuilder problems = new StringBuilder();

    if (getPackageName().startsWith("com.google.")) {
      problems.append("- Package name start with com.google.*\n");
    }

    for (Integer id : new Integer[]{R.string.app_id}) {

      String value = getString(id);

      if (value.startsWith("YOUR_")) {
        // needs replacing
        problems.append("- Placeholders(YOUR_*) in ids.xml need updating\n");
        break;
      }
    }

    if (problems.length() > 0) {
      problems.insert(0, "The following problems were found:\n\n");

      problems.append("\nThese problems may prevent the app from working properly.");
      problems.append("\n\nSee the TODO window in Android Studio for more information");
      (new AlertDialog.Builder(this)).setMessage(problems.toString())
          .setNeutralButton(android.R.string.ok, null).create().show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    Log.d(TAG, "onResume()");

    // Since the state of the signed in user can change when the activity is not active
    // it is recommended to try and sign in silently from when the app resumes.
    signInSilently();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {

    // Player wants to force save or load.
    // NOTE: this button exists in this sample for debug purposes and so that you can
    // see the effects immediately. A game probably shouldn't have a "Load/Save"
    // button (or at least not one that's so prominently displayed in the UI).
    if (item.getItemId() == R.id.menu_sync) {
      loadFromSnapshot(null);
      return true;
    }
    if (item.getItemId() == R.id.menu_save) {
      saveSnapshot(null);
      return true;
    }
    if (item.getItemId() == R.id.menu_select) {
      selectSnapshot();
    }
    return false;
  }


  @Override
  protected void onStart() {
    updateUi();
    super.onStart();

    checkPlaceholderIds();
  }

  public static boolean verifySampleSetup(AppCompatActivity activity, int... resIds) {
    StringBuilder problems = new StringBuilder();
    boolean problemFound = false;
    problems.append("The following set up problems were found:\n\n");

    // Did the developer forget to change the package name?
    if (activity.getPackageName().startsWith("com.google.example.games")) {
      problemFound = true;
      problems.append("- Package name cannot be com.google.*. You need to change the "
          + "sample's package name to your own package.").append("\n");
    }

    for (int i : resIds) {
      if (activity.getString(i).startsWith("YOUR_")) {
        problemFound = true;
        problems.append("- You must replace `YOUR_*`" +
            "placeholder IDs in the ids.xml file by your project's IDs.").append("\n");
        break;
      }
    }

    if (problemFound) {
      problems.append("\n\nThese problems may prevent the app from working properly.");
      (new AlertDialog.Builder(activity)).setMessage(problems.toString())
          .setNeutralButton(android.R.string.ok, null).create().show();
      return false;
    }

    return true;
  }

  @Override
  protected void onStop() {
    if (mLoadingDialog != null) {
      mLoadingDialog.dismiss();
      mLoadingDialog = null;
    }
    super.onStop();
  }


  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.activity_main, menu);
    return true;
  }


  // The currently signed in account, used to check the account has changed outside of this activity when resuming.
  GoogleSignInAccount mSignedInAccount = null;

  private void onConnected(GoogleSignInAccount googleSignInAccount) {
    Log.d(TAG, "onConnected(): connected to Google APIs");
    if (mSignedInAccount != googleSignInAccount) {

      mSignedInAccount = googleSignInAccount;

      onAccountChanged(googleSignInAccount);
    } else {
      updateUi();
    }
  }

  private void onAccountChanged(GoogleSignInAccount googleSignInAccount) {
    mSnapshotsClient = Games.getSnapshotsClient(this, googleSignInAccount);

    // Sign-in worked!
    log("Sign-in successful! Loading game state from cloud.");

    showSignOutBar();

    showSnapshots(getString(R.string.title_load_game), false, false);
  }

  private void onDisconnected() {

    Log.d(TAG, "onDisconnected()");

    mSnapshotsClient = null;
    showSignInBar();
  }


  @Override
  public void onBackPressed() {
    if (mInLevel) {
      updateUi();
      findViewById(R.id.screen_gameplay).setVisibility(View.GONE);
      findViewById(R.id.screen_main).setVisibility(View.VISIBLE);
      mInLevel = false;
    } else {
      super.onBackPressed();
    }
  }

  /**
   * Called when the "sign in" or "sign out" button is clicked.
   */
  @Override
  public void onClick(View view) {
    switch (view.getId()) {
      case R.id.button_sign_in:
        // start the sign-in flow
        Log.d(TAG, "Sign-in button clicked");
        startSignInIntent();
        return;

      case R.id.button_sign_out:
        // sign out.
        signOut();
        showSignInBar();
        mSaveGame = new SaveGame();
        updateUi();
        return;
    }

    if (!isSignedIn()) {
      // All other buttons force the user to sign in.
      new AlertDialog.Builder(this)
          .setMessage(getString(R.string.please_sign_in))
          .setNeutralButton(android.R.string.ok, null)
          .create()
          .show();

      return;
    }

    switch (view.getId()) {
      case R.id.button_next_world:
        if (mWorld < WORLD_MAX) {
          mWorld++;
          updateUi();
        }
        break;
      case R.id.button_prev_world:
        if (mWorld > WORLD_MIN) {
          mWorld--;
          updateUi();
        }
        break;
      default:
        for (int i = 0; i < LEVEL_BUTTON_IDS.length; ++i) {
          if (view.getId() == LEVEL_BUTTON_IDS[i]) {
            launchLevel(i + 1);
            return;
          }
        }
    }
  }

  private boolean isSignedIn() {
    return mSnapshotsClient != null;
  }

  /**
   * Gets a screenshot to use with snapshots. Note that in practice you probably do not want to
   * use this approach because tablet screen sizes can become pretty large and because the image
   * will contain any UI and layout surrounding the area of interest.
   */
  Bitmap getScreenShot() {
    View root = findViewById(R.id.screen_main);
    Bitmap coverImage;
    try {
      root.setDrawingCacheEnabled(true);
      Bitmap base = root.getDrawingCache();
      coverImage = base.copy(base.getConfig(), false /* isMutable */);
    } catch (Exception ex) {
      Log.i(TAG, "Failed to create screenshot", ex);
      coverImage = null;
    } finally {
      root.setDrawingCacheEnabled(false);
    }
    return coverImage;
  }

  /**
   * Since a lot of the operations use tasks, we can use a common handler for whenever one fails.
   *
   * @param exception The exception to evaluate.  Will try to display a more descriptive reason for the exception.
   * @param details   Will display alongside the exception if you wish to provide more details for why the exception
   *                  happened
   */
  private void handleException(Exception exception, String details) {
    int status = 0;

    if (exception instanceof ApiException) {
      ApiException apiException = (ApiException) exception;
      status = apiException.getStatusCode();
    }

    String message = getString(R.string.status_exception_error, details, status, exception);

    new AlertDialog.Builder(MainActivity.this)
        .setMessage(message)
        .setNeutralButton(android.R.string.ok, null)
        .show();

    // Note that showing a toast is done here for debugging. Your application should
    // resolve the error appropriately to your app.
    if (status == GamesClientStatusCodes.SNAPSHOT_NOT_FOUND) {
      Log.i(TAG, "Error: Snapshot not found");
      Toast.makeText(getBaseContext(), "Error: Snapshot not found",
          Toast.LENGTH_SHORT).show();
    } else if (status == GamesClientStatusCodes.SNAPSHOT_CONTENTS_UNAVAILABLE) {
      Log.i(TAG, "Error: Snapshot contents unavailable");
      Toast.makeText(getBaseContext(), "Error: Snapshot contents unavailable",
          Toast.LENGTH_SHORT).show();
    } else if (status == GamesClientStatusCodes.SNAPSHOT_FOLDER_UNAVAILABLE) {
      Log.i(TAG, "Error: Snapshot folder unavailable");
      Toast.makeText(getBaseContext(), "Error: Snapshot folder unavailable.",
          Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * Shows the user's snapshots.
   */
  void showSnapshots(String title, boolean allowAdd, boolean allowDelete) {
    int maxNumberOfSavedGamesToShow = 5;
    SnapshotCoordinator.getInstance().getSelectSnapshotIntent(
        mSnapshotsClient, title, allowAdd, allowDelete, maxNumberOfSavedGamesToShow)
        .addOnCompleteListener(new OnCompleteListener<Intent>() {
          @Override
          public void onComplete(@NonNull Task<Intent> task) {
            if (task.isSuccessful()) {
              startActivityForResult(task.getResult(), RC_LIST_SAVED_GAMES);
            } else {
              handleException(task.getException(), getString(R.string.show_snapshots_error));
            }
          }
        });
  }

  private Task<SnapshotsClient.DataOrConflict<Snapshot>> waitForClosedAndOpen(final SnapshotMetadata snapshotMetadata) {

    final boolean useMetadata = snapshotMetadata != null && snapshotMetadata.getUniqueName() != null;
    if (useMetadata) {
      Log.i(TAG, "Opening snapshot using metadata: " + snapshotMetadata);
    } else {
      Log.i(TAG, "Opening snapshot using currentSaveName: " + currentSaveName);
    }

    final String filename = useMetadata ? snapshotMetadata.getUniqueName() : currentSaveName;

    return SnapshotCoordinator.getInstance()
        .waitForClosed(filename)
        .addOnFailureListener(new OnFailureListener() {
          @Override
          public void onFailure(@NonNull Exception e) {
            handleException(e, "There was a problem waiting for the file to close!");
          }
        })
        .continueWithTask(new Continuation<Result, Task<SnapshotsClient.DataOrConflict<Snapshot>>>() {
          @Override
          public Task<SnapshotsClient.DataOrConflict<Snapshot>> then(@NonNull Task<Result> task) throws Exception {
            Task<SnapshotsClient.DataOrConflict<Snapshot>> openTask = useMetadata
                ? SnapshotCoordinator.getInstance().open(mSnapshotsClient, snapshotMetadata)
                : SnapshotCoordinator.getInstance().open(mSnapshotsClient, filename, true);
            return openTask.addOnFailureListener(new OnFailureListener() {
              @Override
              public void onFailure(@NonNull Exception e) {
                handleException(e,
                    useMetadata
                        ? getString(R.string.error_opening_metadata)
                        : getString(R.string.error_opening_filename)
                );
              }
            });
          }
        });
  }

  /**
   * Loads a Snapshot from the user's synchronized storage.
   */
  void loadFromSnapshot(final SnapshotMetadata snapshotMetadata) {
    if (mLoadingDialog == null) {
      mLoadingDialog = new ProgressDialog(this);
      mLoadingDialog.setMessage(getString(R.string.loading_from_cloud));
    }

    mLoadingDialog.show();

    waitForClosedAndOpen(snapshotMetadata)
        .addOnSuccessListener(new OnSuccessListener<SnapshotsClient.DataOrConflict<Snapshot>>() {
          @Override
          public void onSuccess(SnapshotsClient.DataOrConflict<Snapshot> result) {

            // if there is a conflict  - then resolve it.
            Snapshot snapshot = processOpenDataOrConflict(RC_LOAD_SNAPSHOT, result, 0);

            if (snapshot == null) {
              Log.w(TAG, "Conflict was not resolved automatically, waiting for user to resolve.");
            } else {
              try {
                readSavedGame(snapshot);
                Log.i(TAG, "Snapshot loaded.");
              } catch (IOException e) {
                Log.e(TAG, "Error while reading snapshot contents: " + e.getMessage());
              }
            }

            SnapshotCoordinator.getInstance().discardAndClose(mSnapshotsClient, snapshot)
                .addOnFailureListener(new OnFailureListener() {
                  @Override
                  public void onFailure(@NonNull Exception e) {
                    handleException(e, "There was a problem discarding the snapshot!");
                  }
                });

            if (mLoadingDialog != null && mLoadingDialog.isShowing()) {
              mLoadingDialog.dismiss();
              mLoadingDialog = null;
            }
            hideAlertBar();
            updateUi();
          }
        });
  }

  private void readSavedGame(Snapshot snapshot) throws IOException {
    mSaveGame = new SaveGame(snapshot.getSnapshotContents().readFully());
  }

  /**
   * Conflict resolution for when Snapshots are opened.
   *
   * @param requestCode - the request currently being processed.  This is used to forward on the
   *                    information to another activity, or to send the result intent.
   * @param result      The open snapshot result to resolve on open.
   * @param retryCount  - the current iteration of the retry.  The first retry should be 0.
   * @return The opened Snapshot on success; otherwise, returns null.
   */
  Snapshot processOpenDataOrConflict(int requestCode,
                                     SnapshotsClient.DataOrConflict<Snapshot> result,
                                     int retryCount) {

    retryCount++;

    if (!result.isConflict()) {
      return result.getData();
    }

    Log.i(TAG, "Open resulted in a conflict!");

    SnapshotsClient.SnapshotConflict conflict = result.getConflict();
    final Snapshot snapshot = conflict.getSnapshot();
    final Snapshot conflictSnapshot = conflict.getConflictingSnapshot();

    ArrayList<Snapshot> snapshotList = new ArrayList<Snapshot>(2);
    snapshotList.add(snapshot);
    snapshotList.add(conflictSnapshot);

    // Display both snapshots to the user and allow them to select the one to resolve.
    selectSnapshotItem(requestCode, snapshotList, conflict.getConflictId(), retryCount);

    // Since we are waiting on the user for input, there is no snapshot available; return null.
    return null;
  }

  /**
   * Handles resolving the snapshot conflict asynchronously.
   *
   * @param requestCode      - the request currently being processed.  This is used to forward on the
   *                         information to another activity, or to send the result intent.
   * @param conflictId       - the id of the conflict being resolved.
   * @param retryCount       - the current iteration of the retry.  The first retry should be 0.
   * @param snapshotMetadata - the metadata of the snapshot that is selected to resolve the conflict.
   */
  private Task<SnapshotsClient.DataOrConflict<Snapshot>> resolveSnapshotConflict(final int requestCode,
                                                                                 final String conflictId,
                                                                                 final int retryCount,
                                                                                 final SnapshotMetadata snapshotMetadata) {

    Log.i(TAG, "Resolving conflict retry count = " + retryCount + " conflictid = " + conflictId);
    return waitForClosedAndOpen(snapshotMetadata)
        .continueWithTask(new Continuation<SnapshotsClient.DataOrConflict<Snapshot>, Task<SnapshotsClient.DataOrConflict<Snapshot>>>() {
          @Override
          public Task<SnapshotsClient.DataOrConflict<Snapshot>> then(@NonNull Task<SnapshotsClient.DataOrConflict<Snapshot>> task) throws Exception {
            return SnapshotCoordinator.getInstance().resolveConflict(
                mSnapshotsClient,
                conflictId,
                task.getResult().getData())
                .addOnCompleteListener(new OnCompleteListener<SnapshotsClient.DataOrConflict<Snapshot>>() {
                  @Override
                  public void onComplete(@NonNull Task<SnapshotsClient.DataOrConflict<Snapshot>> task) {
                    if (!task.isSuccessful()) {
                      handleException(
                          task.getException(),
                          "There was a problem opening a file for resolving the conflict!");
                      return;
                    }

                    Snapshot snapshot = processOpenDataOrConflict(requestCode,
                        task.getResult(),
                        retryCount);
                    Log.d(TAG, "resolved snapshot conflict - snapshot is " + snapshot);
                    // if there is a snapshot returned, then pass it along to onActivityResult.
                    // otherwise, another activity will be used to resolve the conflict so we
                    // don't need to do anything here.
                    if (snapshot != null) {
                      Intent intent = new Intent("");
                      intent.putExtra(SelectSnapshotActivity.SNAPSHOT_METADATA, snapshot.getMetadata().freeze());
                      onActivityResult(requestCode, RESULT_OK, intent);
                    }
                  }
                });
          }
        });
  }

  /**
   * Prepares saving Snapshot to the user's synchronized storage, conditionally resolves errors,
   * and stores the Snapshot.
   */
  void saveSnapshot(final SnapshotMetadata snapshotMetadata) {
    waitForClosedAndOpen(snapshotMetadata)
        .addOnCompleteListener(new OnCompleteListener<SnapshotsClient.DataOrConflict<Snapshot>>() {
          @Override
          public void onComplete(@NonNull Task<SnapshotsClient.DataOrConflict<Snapshot>> task) {
            SnapshotsClient.DataOrConflict<Snapshot> result = task.getResult();
            Snapshot snapshotToWrite = processOpenDataOrConflict(RC_SAVE_SNAPSHOT, result, 0);

            if (snapshotToWrite == null) {
              // No snapshot available yet; waiting on the user to choose one.
              return;
            }

            Log.d(TAG, "Writing data to snapshot: " + snapshotToWrite.getMetadata().getUniqueName());
            writeSnapshot(snapshotToWrite)
                .addOnCompleteListener(new OnCompleteListener<SnapshotMetadata>() {
                  @Override
                  public void onComplete(@NonNull Task<SnapshotMetadata> task) {
                    if (task.isSuccessful()) {
                      Log.i(TAG, "Snapshot saved!");
                    } else {
                      handleException(task.getException(), getString(R.string.write_snapshot_error));
                    }
                  }
                });
          }
        });
  }

  /**
   * Generates metadata, takes a screenshot, and performs the write operation for saving a
   * snapshot.
   */
  private Task<SnapshotMetadata> writeSnapshot(Snapshot snapshot) {
    // Set the data payload for the snapshot.
    snapshot.getSnapshotContents().writeBytes(mSaveGame.toBytes());

    // Save the snapshot.
    SnapshotMetadataChange metadataChange = new SnapshotMetadataChange.Builder()
        .setCoverImage(getScreenShot())
        .setDescription("Modified data at: " + Calendar.getInstance().getTime())
        .build();
    return SnapshotCoordinator.getInstance().commitAndClose(mSnapshotsClient, snapshot, metadataChange);
  }


  /**
   * Shows the "sign in" bar (explanation and button).
   */
  private void showSignInBar() {
    findViewById(R.id.sign_in_bar).setVisibility(View.VISIBLE);
    findViewById(R.id.sign_out_bar).setVisibility(View.GONE);
  }

  /**
   * Shows the "sign out" bar (explanation and button).
   */
  private void showSignOutBar() {
    findViewById(R.id.sign_in_bar).setVisibility(View.GONE);
    findViewById(R.id.sign_out_bar).setVisibility(View.VISIBLE);
  }


  /**
   * Updates the game UI.
   */
  private void updateUi() {
    ((TextView) findViewById(R.id.world_display)).setText(getString(R.string.world)
        + " " + mWorld);
    for (int i = 0; i < LEVELS_PER_WORLD; i++) {
      int levelNo = i + 1; // levels are numbered from 1
      Button b = (Button) findViewById(LEVEL_BUTTON_IDS[i]);
      int stars = mSaveGame.getLevelStars(mWorld, levelNo);
      b.setTextColor(getResources().getColor(stars > 0 ? R.color.ClearedLevelColor :
          R.color.UnclearedLevelColor));
      b.setText(String.valueOf(mWorld) + "-" + String.valueOf(levelNo) + "\n" +
          STAR_STRINGS[stars]);
    }
    // disable world changing if we are at the end of the list.
    Button button;

    button = (Button) findViewById(R.id.button_next_world);
    button.setEnabled(mWorld < WORLD_MAX);

    button = (Button) findViewById(R.id.button_prev_world);
    button.setEnabled(mWorld > WORLD_MIN);
  }


  /**
   * Loads the specified level state.
   *
   * @param level - level to load.
   */
  private void launchLevel(int level) {
    mLevel = level;
    ((TextView) findViewById(R.id.gameplay_level_display)).setText(
        getString(R.string.level) + " " + mWorld + "-" + mLevel);
    ((RatingBar) findViewById(R.id.gameplay_rating)).setRating(
        mSaveGame.getLevelStars(mWorld, mLevel));
    findViewById(R.id.screen_gameplay).setVisibility(View.VISIBLE);
    findViewById(R.id.screen_main).setVisibility(View.GONE);
    mInLevel = true;
  }


  @Override
  public void onRatingChanged(RatingBar ratingBar, float rating, boolean fromUser) {
    mSaveGame.setLevelStars(mWorld, mLevel, (int) rating);
    updateUi();
    findViewById(R.id.screen_gameplay).setVisibility(View.GONE);
    findViewById(R.id.screen_main).setVisibility(View.VISIBLE);

    mInLevel = false;
    // save new data to cloud
    saveSnapshot(null);
  }

  /**
   * Prints a log message (convenience method).
   */
  void log(String message) {
    Log.d(TAG, message);
  }


  /**
   * Shows an alert message.
   */
  private void showAlertBar(int resId) {
    ((TextView) findViewById(R.id.alert_bar)).setText(getString(resId));
    findViewById(R.id.alert_bar).setVisibility(View.VISIBLE);
  }


  /**
   * Dismisses the previously displayed alert message.
   */
  private void hideAlertBar() {
    View alertBar = findViewById(R.id.alert_bar);
    if (alertBar != null && alertBar.getVisibility() != View.GONE) {
      alertBar.setVisibility(View.GONE);
    }
  }


  /**
   * This is an example of how to call Games.Snapshots.load(). It displays another
   * activity to allow the user to select the snapshot.  It is recommended to use the
   * standard selection intent, Games.Snapshots.getSelectSnapshotIntent().
   */
  private void selectSnapshot() {
    if (mLoadingDialog == null) {
      mLoadingDialog = new ProgressDialog(this);
      mLoadingDialog.setMessage(getString(R.string.loading_from_cloud));
    }
    mLoadingDialog.show();

    SnapshotCoordinator.getInstance().load(mSnapshotsClient, false)
        .addOnCompleteListener(new OnCompleteListener<AnnotatedData<SnapshotMetadataBuffer>>() {
          @Override
          public void onComplete(@NonNull Task<AnnotatedData<SnapshotMetadataBuffer>> task) {

            if (mLoadingDialog != null && mLoadingDialog.isShowing()) {
              mLoadingDialog.dismiss();
              mLoadingDialog = null;
            }

            if (!task.isSuccessful()) {
              handleException(task.getException(), "There was a problem selecting a snapshot!");
            } else {
              AnnotatedData<SnapshotMetadataBuffer> result = task.getResult();

              if (result.isStale()) {
                Log.w(TAG, "The selected snapshot result was stale!");
              }

              ArrayList<SnapshotMetadata> items = new ArrayList<SnapshotMetadata>();
              SnapshotMetadataBuffer snapshotMetadatas = result.get();
              if (snapshotMetadatas != null) {
                for (SnapshotMetadata m : snapshotMetadatas) {
                  items.add(m);
                }
              }
              selectSnapshotItem(RC_SELECT_SNAPSHOT, items);
            }

          }
        });
  }

  private void selectSnapshotItem(int requestCode,
                                  ArrayList<Snapshot> items,
                                  String conflictId,
                                  int retryCount) {

    ArrayList<SnapshotMetadata> snapshotList = new ArrayList<SnapshotMetadata>(items.size());
    for (Snapshot m : items) {
      snapshotList.add(m.getMetadata().freeze());
    }
    Intent intent = new Intent(this, SelectSnapshotActivity.class);
    intent.putParcelableArrayListExtra(SelectSnapshotActivity.SNAPSHOT_METADATA_LIST,
        snapshotList);

    intent.putExtra(SelectSnapshotActivity.CONFLICT_ID, conflictId);
    intent.putExtra(SelectSnapshotActivity.RETRY_COUNT, retryCount);

    Log.d(TAG, "Starting activity to select snapshot");
    startActivityForResult(intent, requestCode);
  }

  private void selectSnapshotItem(int requestCode, ArrayList<SnapshotMetadata> items) {

    ArrayList<SnapshotMetadata> metadataArrayList =
        new ArrayList<SnapshotMetadata>(items.size());
    for (SnapshotMetadata m : items) {
      metadataArrayList.add(m.freeze());
    }
    Intent intent = new Intent(this, SelectSnapshotActivity.class);
    intent.putParcelableArrayListExtra(SelectSnapshotActivity.SNAPSHOT_METADATA_LIST,
        metadataArrayList);

    startActivityForResult(intent, requestCode);
  }
}
