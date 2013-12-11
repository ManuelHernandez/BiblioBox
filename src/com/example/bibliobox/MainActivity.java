package com.example.bibliobox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.DropboxAPI.Entry;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.android.AuthActivity;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.Session.AccessType;
import com.dropbox.client2.session.TokenPair;


public class MainActivity extends Activity implements OnItemSelectedListener {

	private static final String TAG = "BiblioBox";

	
	//Clave publica y clave secreta para autenticarse en Dropbox
	final static private String APP_KEY = "vc96nkeiiu44f2q";
	final static private String APP_SECRET = "ibig8gkyk6ynkgl";
    
	//Tipo de acceso al servidor de Dropbox
	final static private AccessType ACCESS_TYPE = AccessType.DROPBOX;

	//Constantes para guardar la autenticacion con Dropbox
    final static private String ACCOUNT_PREFS_NAME = "prefs";
    final static private String ACCESS_KEY_NAME = "ACCESS_KEY";
    final static private String ACCESS_SECRET_NAME = "ACCESS_SECRET";
    
    //Cliente para comunicarnos con Dropbox
    DropboxAPI<AndroidAuthSession> mApi;

    //Directorio en el que vamos a buscar los libros. En este caso es el directorio raiz
    private final String LIBROS_DIR = "/";

    private boolean mLoggedIn;

    // Android widgets
    private Button mSubmit;
    private LinearLayout mDisplay;
    private Spinner spinner;
	private ListView listView;
	List<Entry> librosDropbox;
	ArrayList<String> nombreLibros;

    private String mCameraFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCameraFileName = savedInstanceState.getString("mCameraFileName");
        }

        // Creamos una sesion para trabajar con Dropbox API.
        AndroidAuthSession session = buildSession();
        mApi = new DropboxAPI<AndroidAuthSession>(session);

        // Configuramos el layout a mostrar
		setContentView(R.layout.activity_main);

		// Comprobamos que la configuracion es correcta
        checkAppKeySetup();

        // Configuramos el listener del boton para conectarnos y desconectarnos con Dropbox
        mSubmit = (Button)findViewById(R.id.auth_button);
        mSubmit.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // This logs you out if you're logged in, or vice versa
                if (mLoggedIn) {
                    logOut();
                } else {
                    // Start the remote authentication
                    mApi.getSession().startAuthentication(MainActivity.this);
                }
            }
        }); 

        // Representa el layout que contiene la informacion de los libros
        mDisplay = (LinearLayout)findViewById(R.id.logged_in_display);

        
        // Configuramos las opciones del menu desplegable que muestra las opciones de filtrado
        spinner = (Spinner) findViewById(R.id.spinner);
        String[] valores = {"Ordenar por título", "Ordenar por fecha"};
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, valores));
        spinner.setOnItemSelectedListener(this);        
        
        
        // Representa la lista que muestra cada uno de los libros
        listView = (ListView) findViewById(R.id.listView);

        APITask apiTask = new APITask();
        apiTask.execute();
        
        // Vemos si mostramos o no el layout con la informacion de los libros
        setLoggedIn(mApi.getSession().isLinked());

    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("mCameraFileName", mCameraFileName);
        super.onSaveInstanceState(outState);
    }

    /**
     * This is what gets called on finishing a media piece to import
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        
    }


    /**
     * Cierra la sesion con Dropbox
     */
    private void logOut() {
        // Remove credentials from the session
        mApi.getSession().unlink();

        // Clear our stored keys
        clearKeys();
        // Change UI state to display logged out version
        setLoggedIn(false);
    }

    /**
     * Convenience function to change UI state based on being logged in
     */
    private void setLoggedIn(boolean loggedIn) {
    	mLoggedIn = loggedIn;
    	if (loggedIn) {
    		mSubmit.setText("Unlink from Dropbox");
            mDisplay.setVisibility(View.VISIBLE);
    	} else {
    		mSubmit.setText("Link with Dropbox");
            mDisplay.setVisibility(View.GONE);
    	}
    }

    /**
     * Comprobamos que la configuracion de la clave es correcta
     */
    private void checkAppKeySetup() {
        // Check to make sure that we have a valid app key
        if (APP_KEY.startsWith("CHANGE") ||
                APP_SECRET.startsWith("CHANGE")) {
            showToast("You must apply for an app key and secret from developers.dropbox.com, and add them to the DBRoulette ap before trying it.");
            finish();
            return;
        }

        // Check if the app has set up its manifest properly.
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        String scheme = "db-" + APP_KEY;
        String uri = scheme + "://" + AuthActivity.AUTH_VERSION + "/test";
        testIntent.setData(Uri.parse(uri));
        PackageManager pm = getPackageManager();
        if (0 == pm.queryIntentActivities(testIntent, 0).size()) {
            showToast("URL scheme in your app's " +
                    "manifest is not set up correctly. You should have a " +
                    "com.dropbox.client2.android.AuthActivity with the " +
                    "scheme: " + scheme);
            finish();
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        error.show();
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     *
     * @return Array of [access_key, access_secret], or null if none stored
     */
    private String[] getKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key != null && secret != null) {
        	String[] ret = new String[2];
        	ret[0] = key;
        	ret[1] = secret;
        	return ret;
        } else {
        	return null;
        }
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void storeKeys(String key, String secret) {
        // Save the access key for later
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.putString(ACCESS_KEY_NAME, key);
        edit.putString(ACCESS_SECRET_NAME, secret);
        edit.commit();
    }

    
    /**
     * Limpia los datos de conexion guardados
     */    
    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }

    /**
     * Genera un objeto sesion para proceder con la autenticacion
     */        
    private AndroidAuthSession buildSession() {
        AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);
        AndroidAuthSession session;

        String[] stored = getKeys();
        if (stored != null) {
            AccessTokenPair accessToken = new AccessTokenPair(stored[0], stored[1]);
            session = new AndroidAuthSession(appKeyPair, ACCESS_TYPE, accessToken);
        } else {
            session = new AndroidAuthSession(appKeyPair, ACCESS_TYPE);
        }

        return session;
    }
    
    /**
     * Se ejecuta cuando es pulsado un elemento de la lista
     */
    @Override
    public void onItemSelected(AdapterView<?> adapter, View view, int position,
            long id) {
   }
     
    @Override
    public void onNothingSelected(AdapterView<?> adapter) {
     
    }   
    
    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mApi.getSession();

        // The next part must be inserted in the onResume() method of the
        // activity from which session.startAuthentication() was called, so
        // that Dropbox authentication completes properly.
        if (session.authenticationSuccessful()) {
            try {
                // Mandatory call to complete the auth
                session.finishAuthentication();

                // Store it locally in our app for later use
                TokenPair tokens = session.getAccessTokenPair();
                storeKeys(tokens.key, tokens.secret);
                setLoggedIn(true);
            } catch (IllegalStateException e) {
                showToast("Couldn't authenticate with Dropbox:" + e.getLocalizedMessage());
                Log.i(TAG, "Error authenticating", e);
            }
        }
    }
    
    

    
    /**
     * Clase para ejecutar la busqueda contra Dropbox en un Thread que no 
     * sea el principal.
     */    
    
    class APITask extends AsyncTask<Void, Void, Void>{

		@Override
		protected Void doInBackground(Void... params) {

	        try {
	    		librosDropbox = mApi.search(LIBROS_DIR, ".epub", 100, false);
	    		
	    		nombreLibros = new ArrayList<String>();
	    		
	    		for (int i = 0; i < librosDropbox.size(); i++){
	    			Entry libroJson = librosDropbox.get(i);
	       			String path = libroJson.path;
	       			path = path.substring(path.lastIndexOf("/") + 1);
	    			nombreLibros.add(path);
	    		}
	    			
	    	} catch (DropboxException e) {
	            Log.e(TAG, "Error en la busqueda", e);
	    	}
			
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);
		
			
		    Collections.sort(nombreLibros, new Comparator<String>() {
		        @Override
		        public int compare(String s1, String s2) {
		            return s1.compareToIgnoreCase(s2);
		        }
		    });			
			
	        // Construimos un adapter para mostrar los libros en una lista
	        ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this,
	            android.R.layout.simple_list_item_1, nombreLibros);
	        
	        listView.setAdapter(adapter);	
			
		}
    }    
}
