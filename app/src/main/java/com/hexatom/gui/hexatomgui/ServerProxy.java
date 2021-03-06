package com.hexatom.gui.hexatomgui;

import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.content.SharedPreferences;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortOut;
import com.illposed.osc.OSCPortIn;
import com.illposed.osc.OSCListener;
import android.os.Handler;
import java.util.Timer;
import java.util.TimerTask;
import android.content.Context;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import android.os.Vibrator;

public class ServerProxy extends Service {
    //members associated with establishing a connection with the server
    private String InIP;
    private String InPort;
    private String OutIP;
    private String OutPort;
    private OSCPortIn receiver;
    Handler handler;
    Timer requestInformationTimer;
    private Integer seqNum;

    //callback's
    GuiUpdateCallback tempoCallback;
    GuiUpdateCallback messageReceivedCallback;

    private final IBinder binder = new ServerBinder();

    public ServerProxy() {
        this.InIP = "";
        this.InPort = "";
        this.seqNum = 1;
        this.OutIP = "";
        this.OutPort = "";
        requestInformationTimer = null;
        handler = new Handler();
    }

    public void setProxyCredentials(String IP, String Port) {
        this.OutIP = IP;
        this.OutPort = Port;
        this.InIP = getLocalIpAddress();
        this.InPort = "5000";

        try {
            //Initialize variables for use in establishing the OSC connection
            if (receiver != null) {
                receiver.close();
            }
            receiver = new OSCPortIn(Integer.parseInt(this.InPort));
            OSCListener listener = new ServerListener(this);

            //Register the receiver to listen for incoming packets
            receiver.addListener("/interpret", listener);
            receiver.startListening();
        } catch (final java.net.SocketException sx) {
            Log.e("ServerProxy","Socket Exception on OSC Listener.");
        } catch (final Exception e) {
            Log.e("ServerProxy","Exception starting OSC Listener");
        }

        if(requestInformationTimer == null)
        requestInformationTimer = new Timer();
        try {
            requestInformationTimer.schedule(doAsynchronousTask,0,2500);
        }catch(IllegalStateException e){
            Log.e("RequestTimer","Task was already scheduled or cancelled, timer was " +
                    "cancelled, or timer thread terminated");
        }
    }

    /**
     * Must implement GuiUpdateCallback to register with a ServerProxy update.
     */
    interface GuiUpdateCallback{
        public void update(String value);
    }

    public void tempoRegister(GuiUpdateCallback callback){
        tempoCallback = callback;
    }

    private void updateTempo(String val){
        if(tempoCallback != null){
            tempoCallback.update(val);
        }
    }

    public void messageReceivedRegister(GuiUpdateCallback callback){
        messageReceivedCallback = callback;
    }

    private void notifyMessageReceivedByServer(){
        if(messageReceivedCallback != null){
            messageReceivedCallback.update("");
        }
        Vibrator v = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(100);
    }

    public void queryGameStateFromServer(){
        this.sendMessage("qt");
    }

    public void updateViews(java.util.Date time,OSCMessage message){

        Object [] args = message.getArguments();

        //check for oscmessage error
        if((args[1].toString()).equals("false")) {
            Log.e("OSCMessage:", args[2].toString());
            return;
        }

        //message is for updating
        for (int i = 2; i < args.length ; i++) {
            try {
                String arg = args[i].toString();
                String msg = arg.substring(1, arg.length() - 1);
                String delims = "=";
                String[] KeyVal = msg.split(delims);
                String key = KeyVal[0];
                Log.e("OSCMessage:", key);
                String value = KeyVal[1];

                if (key.equals("tempo")) {
                    updateTempo(value);
                }
            }catch(Exception ex){
                //Log.e("Serverproxy.updateViews","Error.");
                //ex.printStackTrace();
                notifyMessageReceivedByServer();
            }
        }
    }

    public void runOnUiThread(Runnable runnable) {
        handler.post(runnable);
    }

    public boolean isConnected(){
        if((this.InIP.equals("")) || (this.InPort.equals("")) || (this.OutIP.equals(""))
                || (this.OutPort.equals("")) || (this.receiver == null)){
            return false;
        }
        return true;
    }

    /**
     * Send a message to the server. Will return true if successful and false otherwise.
     *
     * @param message message to send to the server via OSC
     * @return true if message was successfully send, false otherwise. Note this does not
     * guarantee the message was received by the server.
     */
    public synchronized boolean sendMessage(String message) {
        //first if no connection is established, try to regain a connection.
        //this may occur if the service is unbound and restarts
        if(!this.isConnected()){
            SharedPreferences hexAtomConfig = getSharedPreferences("HexAtomConfig", MODE_PRIVATE);
            String IP = hexAtomConfig.getString("defaultIP", "");
            String Port = hexAtomConfig.getString("defaultPort", "");
            setProxyCredentials(IP,Port);
        }

        try {
            Object[] oscargs = new Object[4];

            //Bundle up the incoming IP address, incoming port number, sequence number, and command
            oscargs[0] = InIP;
            oscargs[1] = Integer.parseInt(InPort);
            oscargs[2] = seqNum;
            oscargs[3] = message;

            //Initialize the OSC object that will actually send the OSC packet to HexAtom
            OSCPortOut sender = null;

            //Set the IP address and port of the server to which packets will be sent
            if (OutIP != "" && OutPort != "") {
                InetAddress otherIP = InetAddress.getByName(OutIP);
                try {
                    sender = new OSCPortOut(otherIP, Integer.parseInt(OutPort));
                } catch (SocketException e) {
                    e.printStackTrace();
                    Log.e("ServerProxy", "Failed to create new OSCPortOut");
                    return false;
                }
            } else {
                //out ip and port were not yet set!
                Log.e("ServerProxy", "Null OutIP/Port");
                return false;
            }
            //Send the bundled information to HexAtom
            try {
                sender.send(new OSCMessage("/interpret", oscargs));
            } catch (final IOException e) {
                e.printStackTrace();
                Log.e("ServerProxy", "Interpret failed for OSCMessage");
                return false;
            }

        } catch (final UnknownHostException ux) {
            ux.printStackTrace();
            Log.e("ServerProxy", "UnknownHostException was thrown.");
            return false;
        }
        this.seqNum++;
        return true;
    }

    TimerTask doAsynchronousTask = new TimerTask() {
        @Override
        public void run() {
            handler.post(new Runnable() {
                @SuppressWarnings("unchecked")
                public void run() {
                    try {
                        queryGameStateFromServer();
                    }
                    catch (Exception e) {
                        // TODO Auto-generated catch block
                    }
                }
            });
        }
    };

    public class ServerBinder extends Binder {
        ServerProxy getService() {
            //return an instance of server proxy so clients can
            //call the public methods
            return ServerProxy.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }


    /**
     * @return
     */
    public String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ipAddress = wifiInfo.getIpAddress();
            String ip = intToIp(ipAddress);
            return ip;
        } catch (Exception ex) {
            ex.printStackTrace();
            Log.e("ServerProxy","Error getting local IP.");
        }
        return null;
    }

    /**
     *
     * @param i
     * @return
     */
    public String intToIp(int i) {

        return ((i & 0xFF) + "." +
                ((i >> 8) & 0xFF) + "." +
                ((i >> 16) & 0xFF) + "." +
                ((i >> 24) & 0xFF));


    }
}
