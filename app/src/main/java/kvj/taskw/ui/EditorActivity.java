package kvj.taskw.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import org.kvj.bravo7.form.FormController;
import org.kvj.bravo7.form.impl.ViewFinder;
import org.kvj.bravo7.form.impl.bundle.StringBundleAdapter;
import org.kvj.bravo7.form.impl.widget.TransientAdapter;
import org.kvj.bravo7.log.Logger;
import org.kvj.bravo7.util.Tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kvj.taskw.App;
import kvj.taskw.R;
import kvj.taskw.data.AccountController;
import kvj.taskw.data.Controller;

/**
 * Created by kvorobyev on 11/21/15.
 */
public class EditorActivity extends AppCompatActivity {

    private Toolbar toolbar = null;
    private Editor editor = null;
    private FormController form = new FormController(new ViewFinder.ActivityViewFinder(this));
    Controller controller = App.controller();
    Logger logger = Logger.forInstance(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        toolbar = (Toolbar) findViewById(R.id.editor_toolbar);
        editor = (Editor) getSupportFragmentManager().findFragmentById(R.id.editor_editor);
        setSupportActionBar(toolbar);
        form.add(new TransientAdapter<>(new StringBundleAdapter(), null), App.KEY_ACCOUNT);
        form.add(new TransientAdapter<>(new StringBundleAdapter(), null), App.KEY_EDIT_UUID);
        editor.initForm(form);
        form.load(this, savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        form.save(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_tb_save:
                doSave();
                break;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (!form.changed()) { // No changes - just close
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("There are some changes, discard?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        EditorActivity.super.onBackPressed();
                    }
                }).setNegativeButton("No", null)
                .show();
    }
    
    private String propertyChange(String key, String modifier) {
        String value = form.getValue(key);
        if (TextUtils.isEmpty(value)) {
            value = "";
        }
        return String.format("%s:%s", modifier, value);
    }

    private String save() {
        if (!form.changed()) { // No change - no save
            return null;
        }
        if (TextUtils.isEmpty((String)form.getValue(App.KEY_EDIT_DESCRIPTION))) { // Empty desc
            return "Description is mandatory";
        }
        List<String> changes = new ArrayList<>();
        for (String key : form.changes()) { // Make changes
            if (App.KEY_EDIT_DESCRIPTION.equals(key)) { // Direct
                changes.add((String)form.getValue(App.KEY_EDIT_DESCRIPTION));
            }
            if (App.KEY_EDIT_PROJECT.equals(key)) { // Direct
                changes.add(propertyChange(key, "project"));
            }
            if (App.KEY_EDIT_DUE.equals(key)) { // Direct
                changes.add(propertyChange(key, "due"));
            }
            if (App.KEY_EDIT_SCHEDULED.equals(key)) { // Direct
                changes.add(propertyChange(key, "scheduled"));
            }
            if (App.KEY_EDIT_WAIT.equals(key)) { // Direct
                changes.add(propertyChange(key, "wait"));
            }
            if (App.KEY_EDIT_UNTIL.equals(key)) { // Direct
                changes.add(propertyChange(key, "until"));
            }
            if (App.KEY_EDIT_RECUR.equals(key)) { // Direct
                changes.add(propertyChange(key, "recur"));
            }
            if (App.KEY_EDIT_TAGS.equals(key)) { // Direct
                List<String> tags = new ArrayList<>();
                String tagsStr = form.getValue(App.KEY_EDIT_TAGS);
                Collections.addAll(tags, tagsStr.split(" "));
                changes.add(String.format("tags:%s", MainListAdapter.join(",", tags)));
            }
        }
        String uuid = form.getValue(App.KEY_EDIT_UUID);
        AccountController ac = controller.accountController(form.getValue(App.KEY_ACCOUNT, String.class));
        logger.d("Saving change:", uuid, changes);
        if (TextUtils.isEmpty(uuid)) { // Add new
            return ac.taskAdd(changes);
        } else {
            return ac.taskModify(uuid, changes);
        }
    }

    private void doSave() {
        new Tasks.ActivitySimpleTask<String>(this) {

            @Override
            protected String doInBackground() {
                return save();
            }

            @Override
            public void finish(String result) {
                if (!TextUtils.isEmpty(result)) { // Failed
                    controller.messageLong(result);
                } else {
                    EditorActivity.this.finish();
                }
            }
        }.exec();
    }

}