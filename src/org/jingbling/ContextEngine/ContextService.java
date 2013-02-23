package org.jingbling.ContextEngine;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import libsvm.svm_model;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Created with IntelliJ IDEA.
 * User: Jing
 * Date: 12/27/12
 * Time: 11:44 PM
 * To change this template use File | Settings | File Templates.
 */
public class ContextService extends IntentService {

   // Some variables for defining request
    private String contextGroup = new String();
    private String classifier = new String();
    private ArrayList<String> features =new ArrayList<String>();
    private ArrayList<String> contextLabels =new ArrayList<String>();
    private String JSONDataFile = "/ContextServiceFiles/data/DataStructure.JSON";
    private String trainingFileName;
    private String classifiedModelFile;

    // Create a hash map for each group of values that need to be looked up
    JSONArray allowableValues = new JSONArray();
    private HashMap ContextGroupHashMap = new HashMap();
    private HashMap featuresHashMap = new HashMap();
    private HashMap classifiersHashMap = new HashMap();
    private HashMap classModel = new HashMap();
    private HashMap contextLabelsHashMap = new HashMap();
    private HashMap contextGroup2LabelsHashMap = new HashMap();

    private Bundle outputBundle = new Bundle();
    public boolean activityRunning = false;
    private String action;
    private static ArrayBlockingQueue<String> dataBuffer;
    private boolean runClassify=false;
    private boolean onTick = false;
    private Intent dataCollectIntent;

    private Handler timingHandler = new Handler();
    private long elapsedTime = 0;
    MessageReceiver myReceiver = null;
    Boolean receiverRegistered = false;
    private svm_model currentSVMModel;

    static final int LAUNCH_DATACOLLECT = 1;

    public ContextService(){
        super("ContextService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        // Initialize data arrays from a file
        InitJSONtoHashMap(JSONDataFile);

        //After launching service, first parse JSON from intent
        Bundle inputExtras = intent.getExtras();
        //todo Validate JSON against schema?

        // Add JSON parser call here
        try {
            JSONObject jsonInput = new JSONObject(inputExtras.getString("JSONInput"));
            contextGroup = jsonInput.getString("contextGroup");
            classifier = jsonInput.getString("classifier");
            JSONArray featuresArray = jsonInput.getJSONArray("features");
            for (int i=0;i<featuresArray.length();i++) {
                features.add(i, featuresArray.get(i).toString());
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Using contextGroup, get list of labels associated
        ArrayList<Integer> tempArray = (ArrayList<Integer>) contextGroup2LabelsHashMap.get(contextGroup);
        for (int i=0;i<tempArray.size();i++) {
            // save array values to labels
            contextLabels.add((String) contextLabelsHashMap.get(tempArray.get(i)));
        }

        // Next, calculate unique id from adding the sum of the IDs from the contextGroup, classifier, and features
        // Lookup in the hashmap the corresponding IDs for each, and then for an existing context model file
        // classifiers will have ID 99-100
        // contextGroup should have ID 100-9900
        // And features will have integers n=13-32, representing bit mapped values of 2^n
//
//        int lookupID = (Integer) ContextGroupHashMap.get(contextGroup);
//
//        lookupID = lookupID + (Integer)classifiersHashMap.get(classifier);
//        // Loop through supplied features and add bitmapped value
//        for (int i=0;i<features.size();i++) {
//            int tempValue = (Integer)featuresHashMap.get(features.get(i));
//            lookupID = (int) (lookupID + Math.pow(2, tempValue));
//        }
        // todo don't mess with lookupID at the moment, use bogus number to test training
        int lookupID = 0;

        // Not use lookup ID to determine if there is an existing model to run
        classifiedModelFile = (String)classModel.get(lookupID);

        //todo for now test with known model
        classifiedModelFile = "/mnt/sdcard/ContextServiceModels/LibSVM/test.model";

        action = inputExtras.getString("action");
        if (action.equals("classify")) {
            if (classifiedModelFile == null) {

                // Do not automatically launch activity - instead, return a message telling application to request training activity
                // define output bundle
                outputBundle.putInt("return", 1);
                outputBundle.putString("label","run training");


            }

            if (classifiedModelFile != null) {
                // also parse out frequency to return context
                long period = inputExtras.getLong("period");
                long duration = inputExtras.getLong("duration");
                // initialize buffer for holding input data to classify
                dataBuffer = new ArrayBlockingQueue<String>(1);

                // Create new countdown clock to determine how long and often to get data
                //todo will eventually need to calculate max frequency for multiple requestors

                // start service for grabbing feature data for classifier input
                Bundle extras = new Bundle();
                extras.putInt("labelID", -99); // chose a bogus value as this is not needed for classifying
                extras.putString("labelName", "blah");  // chose a bogus value as this is not needed for classifying
                extras.putStringArrayList("features",features);
                extras.putString("filename","notNeeded");
                extras.putString("action", "classify");

                // todo Use feature server to save desired features to specified file for training

                dataCollectIntent = new Intent(this, FeatureCollectionService.class);
                dataCollectIntent.putExtras(extras);
                startService(dataCollectIntent);

                // set flag to indicate classification should start
                runClassify = true;
                elapsedTime = 0;

                // First set up model before running in a loop
                LearningServer classifierServer = new LearningServer();
                svm_model currentModel=null;
                if (classifier.toLowerCase().equals("libsvm")) {
                    // load model from file

                    try {
                        currentModel = classifierServer.loadSVMModel(classifiedModelFile);
                    } catch (IOException e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }
                HashMap labelHash = new HashMap();
                for (int i=0;i<contextLabels.size();i++) {
                    labelHash.put(i-1,contextLabels.get(i));
                }
                while (runClassify) {
                    // while the duration to run the classifier has not completed, grab input data from feature collection service
                    // and classify and return depending on frequency.
                    // Run classifier model to determine label
                    String classifiedLabel = new String();
                    // first load model

                    if (classifier.toLowerCase().equals("libsvm")) {

                        // Only run classifier once every desired period

                        try {
                            classifiedLabel = classifierServer.evaluateSVMModel(dataBuffer.take(), labelHash, currentModel);
                        } catch (IOException e) {
                            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        } catch (InterruptedException e) {
                            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        }
                    }

                        // define output bundle
                    outputBundle.putInt("return", 0);
                    outputBundle.putString("label",classifiedLabel);
                    if (inputExtras != null) {
                        Messenger messenger = (Messenger) inputExtras.get("MESSENGER");
                        Message msg = Message.obtain();
                        msg.setData(outputBundle);
                        try {
                            messenger.send(msg);
                        } catch (android.os.RemoteException e1) {
                            Log.w(getClass().getName(), "Exception sending message", e1);
                        }

                    }

                    // if we have not yet reached end time, wait a delay
                    if (elapsedTime<duration){
                        elapsedTime+=period;
                        try {
                            HandlerThread.sleep(period);
                        } catch (InterruptedException e) {
                            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        }
                    } else {
                        // max duration reached, end classifying
                        runClassify = false;
                    }
                }


                stopService(dataCollectIntent);

                outputBundle.putInt("return", 0);
                outputBundle.putString("label","classification mode completed");

            }
        } else if(action.equals("train")){ //action = train

            //no classifier found, launch activity to train model
            saveTrainingData(features, contextGroup, contextLabels);
            // set activity running to pause service until return value given from data collection activity
            activityRunning = true;
            // While waiting for Training session to finish, suspend thread
            while(activityRunning) {
                try {
                    HandlerThread.sleep(1000,1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }
//            //todo automatically train data based on training file created
//            if (trainingFileName == null) {
//                // todo training file was not created correctly, return error
//                outputBundle.putInt("return", 1);
//                outputBundle.putString("error", "No training file found");
//                classifiedModelFile = null;
//            } else {
//                // train model and then save trained model
//                // todo create more generic model training function
//                LearningServer trainModel = new LearningServer();
//                File sdCard = Environment.getExternalStorageDirectory();
//                File dir = new File(sdCard.getAbsolutePath() + "/ContextServiceModels/LibSVM");
//                dir.mkdirs();
//                classifiedModelFile = dir.toString()+"/ContextServiceModels/"+classifier+"/Classifier"+lookupID+".model";
//                Log.v("TrainModel", "writing model file: "+ classifiedModelFile);
//
//                try {
//                    trainModel.runSVMTraining(trainingFileName,classifiedModelFile);
//                } catch (IOException e) {
//                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
//                }
//
//                // classifiedModelFile created, update hashmap
//                classifiersHashMap.put(lookupID,classifiedModelFile);
//            }

        }
        // At end of call, pass classified context back to calling application
        if (inputExtras != null) {
            Messenger messenger = (Messenger) inputExtras.get("MESSENGER");
            Message msg = Message.obtain();
            msg.setData(outputBundle);
            try {
                messenger.send(msg);
            } catch (android.os.RemoteException e1) {
                Log.w(getClass().getName(), "Exception sending message", e1);
            }


        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        myReceiver = new MessageReceiver();
        registerReceiver(myReceiver, new IntentFilter("org.jingbling.ContextEngine.ContextService"));
        android.os.Debug.waitForDebugger(); //todo TO BE REMOVED
    }

    @Override
    public void onDestroy() {
        //todo Before exiting, write hashmap to file
        unregisterReceiver(myReceiver);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
//        return Service.BIND_AUTO_CREATE;
    }


    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    public void InitJSONtoHashMap (String inputJSONFile) {

        JSONObject inputJSON = null;
        try {
            inputJSON = parseJSONFromFile(inputJSONFile);
        } catch (JSONException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        JSONArray tempObject = new JSONArray();
        // Parse JSON to establish data mappings for lookup
        try {
            tempObject = inputJSON.getJSONArray("contextGroups");
            for (int i=0;i<tempObject.length();i++) {
                ContextGroupHashMap.put(tempObject.getJSONObject(i).getString("name"),tempObject.getJSONObject(i).getInt("id"));
            }

            tempObject = inputJSON.getJSONArray("contexts");
            for (int i=0;i<tempObject.length();i++) {
                contextLabelsHashMap.put(tempObject.getJSONObject(i).getInt("id"),tempObject.getJSONObject(i).getString("name"));
            }

            tempObject = inputJSON.getJSONArray("contextGroupsContents");
            for (int i=0;i<tempObject.length();i++) {
                JSONArray contextList = new JSONArray();
                contextList = tempObject.getJSONObject(i).getJSONArray("ids");
                ArrayList<Integer> labelList = new ArrayList();

                for (int icount=0;icount<(contextList.length());icount++) {
                    //save array to an arraylist
                    labelList.add(icount, contextList.getInt(icount));
                }
                Log.v("JSONParse","saving labelList: " + labelList);
                contextGroup2LabelsHashMap.put(tempObject.getJSONObject(i).getString("name"),labelList.clone());
                labelList.clear();
            }


            tempObject = inputJSON.getJSONArray("classifiers");
            for (int i=0;i<tempObject.length();i++) {
                // Reverse the order for classifier Models, as lookup will be usually be performed with id
                classifiersHashMap.put(tempObject.getJSONObject(i).getString("name"),tempObject.getJSONObject(i).getInt("id"));
            }

            tempObject = inputJSON.getJSONArray("features");
            for (int i=0;i<tempObject.length();i++) {
                featuresHashMap.put(tempObject.getJSONObject(i).getString("name"),tempObject.getJSONObject(i).getInt("id"));
            }

            tempObject = inputJSON.getJSONArray("classifierModels");
            for (int i=0;i<tempObject.length();i++) {
                // Reverse the order for classifier Models, as lookup will be usually be performed with id
                classModel.put(tempObject.getJSONObject(i).getInt("id"),tempObject.getJSONObject(i).getString("name"));
            }

        } catch (JSONException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }


    }

    public JSONObject parseJSONFromFile (String inputJSONFile) throws JSONException {

        String jString = null;
        // Parse expected elements from JSON file into a JSON Object
        try {

            File dir = Environment.getExternalStorageDirectory();
            File inputFile = new File(dir, inputJSONFile);
            FileInputStream istream = new FileInputStream(inputFile);
            try {
                FileChannel fc = istream.getChannel();
                MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                /* Instead of using default, pass in a decoder. */
                jString = Charset.defaultCharset().decode(bb).toString();
            }
            finally {
                istream.close();
            }

        } catch (Exception e) {e.printStackTrace();}

        if (jString != null) {

            JSONObject outputJSONObj = new JSONObject(jString);
            return outputJSONObj;
        } else {
            Log.w("parseJSONFromFile","ERROR parsing JSONObject from file");
            return null;
        }
    }

    public void writeJSONtoFile (JSONObject inputObject, String outputFileName) {
        //todo Utility function to save internal data in form of JSON Object into a file
    }
//
    // commenting classifyContext out for now as it may be easier to run directly in loop above
//    public String classifyContext(String inputString, String classifierModelFile, String classifierToUse, ArrayList<String> contextLabels) {
//        String returnValue = "toBeImplemented using features and group:" +contextGroup;
////        String inputVectorFilename = "/ContextServiceFiles/input/testinput.txt";
////        File dir = Environment.getExternalStorageDirectory();
////        File inputVectorFile = new File(dir, inputVectorFilename);
//
//        // First use features to Use to save file of input vector
//        // todo Hardcode for now by creating file from input stringbuffer
//
//        // Check on classifier to use, depending on type, run training locally or perform on remote machine
//        if (classifierToUse.toLowerCase().equals("libsvm")) {
//            //if libSVM chosen, run on machine
//            //todo Also need input hashtable for decoding labels
//            // Need to revisit if this is the best way - for now create from input label array list
//            HashMap labelHash = new HashMap();
//            for (int i=0;i<contextLabels.size();i++) {
//                labelHash.put(i-1,contextLabels.get(i));
//            }
//            LearningServer classifierServer = new LearningServer();
//            try {
//                returnValue = classifierServer.evaluateSVMModel(inputString, classifierModelFile, labelHash, currentSVMModel);
//
//            } catch (IOException e) {
//                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
//            }
//        }
//
//        return returnValue;
//    }

    public void saveTrainingData(ArrayList featuresToUse, String contextGroup, ArrayList contextLabels) {
        //launch activity that will guide user through recording labeled data
        Intent dialogIntent = new Intent(getBaseContext(), DataCollectionAct.class);
//        dialogIntent.setAction(Intent.ACTION_VIEW);
//        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dialogIntent.putExtra("features", featuresToUse);
        dialogIntent.putExtra("context", contextGroup);
        dialogIntent.putExtra("contextLabels", contextLabels);

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(serviceMsgHandler);
        dialogIntent.putExtra("SERVICE_MESSENGER", messenger);

//        startActivity(dialogIntent);
        getApplication().startActivity(dialogIntent);
    }


    private Handler serviceMsgHandler = new Handler() {
        public void handleMessage(Message message) {
            Bundle output = message.getData();
            if (output != null) {
                // Also allow for getting final model file from activity
                String fileReceived = output.getString("fileType");
                if (fileReceived.toLowerCase().equals("model")) {
                    classifiedModelFile = output.getString("modelFileName");
                    Toast.makeText(getApplicationContext(),
                            "Model file received: " + classifiedModelFile, Toast.LENGTH_LONG)
                            .show();
                } else if (fileReceived.toLowerCase().equals("trainingdata")) {

                    trainingFileName = output.getString("trainingFile");
                    Toast.makeText(getApplicationContext(),
                            "Training file received: " + trainingFileName, Toast.LENGTH_LONG)
                            .show();
                } else {

                    Toast.makeText(getApplicationContext(),
                            "Unrecognized message received from data collection activity", Toast.LENGTH_LONG)
                            .show();
                }
            } else {
                Toast.makeText(getApplicationContext(), "Classification failed",
                        Toast.LENGTH_LONG).show();
            }
            // after return received from activity, set activity running to false
            activityRunning = false;
        };
    };


    private class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle output = intent.getExtras();
            if (output != null) {
                String input = output.getString("fileType");
                if (input.toLowerCase().equals("datatolabel")){
                    Log.d("CONTEXT_SERVICE","Data input to classify: "+output.getString("inputData"));
                    // put data into a buffer
                    String tempString = output.getString("inputData");
                    // there is new data available, clear existing buffer before adding to ensure classify is done on latest input
                    dataBuffer.clear();
                    dataBuffer.offer(tempString);

                }
            }
        }
    }
//
//    public boolean isActivityRunning() {
//        // function to check if another activity like the training activity is running
//        boolean ActivityRunState = false;
//        // Get list of running tasks
//        ActivityManager activityManager = (ActivityManager)this.getSystemService(Context.ACTIVITY_SERVICE);
//        List<ActivityManager.RunningAppProcessInfo> Activities = activityManager.getRunningAppProcesses();
//        for (int i=0; i < Activities.size(); i++) {
//            Log.v("isActivityRunning", Activities.get(i).toString());
//        }
//        return ActivityRunState;
//    }


//
//    public static <T, E> T getKeyByValue(HashMap<T, E> map, E value) {
//        // Used for reverse lookup of key to value
//        for (Map.Entry<T, E> entry : map.entrySet()) {
//            if (value.equals(entry.getValue())) {
//                return entry.getKey();
//            }
//        }
//        return null;
//    }
}
