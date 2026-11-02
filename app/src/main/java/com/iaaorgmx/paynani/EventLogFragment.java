package com.iaaorgmx.paynani;
//PaynaniApp

import android.Manifest;

import com.iaaorgmx.paynani.db.EventLog;
import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.Inbound;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;


import androidx.core.content.ContextCompat;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EventLogFragment extends Fragment {


    private ListView lstOpciones,lstOpciones_remote;
    View checkBoxView;
    CheckBox checkbox;
    int index_position;
    ImageView btn_recall;

    RelativeLayout progressLayout;
    log_adapter adaptador;
    EventLogRepository eventLog;
    boolean isConnected;
    Boolean isInternetPresent = false; // true or false
    String call_contact;
    String source_tag = "Call Log";
    public static SharedPreferences loginPreferences;
    public static SharedPreferences.Editor loginPrefsEditor;
    String opcionSeleccionada;
    int id_llamada;
    int contador = 0;
    RelativeLayout empty, noempty;
    ImageView contact_photo, photoC;
    LinearLayout item_details;
    //public static PhoneNumberUtil phoneUtil;
    //public static Phonenumber.PhoneNumber phNumberProto = null;

    TextView tv_saving,tv_carrier,tv_calltime,tv_namecontact;
    ImageView icon_Status;

    ArrayList<HashMap<String, String>> remote_call_list = new ArrayList<HashMap<String, String>>();
    JSONArray calls_array = null;

    int CALL_REQUEST_CODE = 1010;
    CountDownTimer waitTimer;
    String number = "";

    BroadcastReceiver mHandleMessageReceiver;
    SessionManager session;
    boolean ishome;

    // El registro se carga en un hilo propio y vuelve al hilo principal con un
    // Handler (antes AsyncTask, obsoleto desde API 30). Un solo hilo conserva el
    // orden en serie que tenía AsyncTask.execute().
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void loadLog() {

        progressLayout.setVisibility(View.VISIBLE);

        logExecutor.execute(new Runnable() {
            @Override
            public void run() {

                // Cada carga trae su propia lista: si llega otro UPDATELOG
                // mientras tanto, no le cambia la lista a esta (#100).
                final ArrayList<LogEvent_Model> calls = getcalls();

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {

                        // Si el fragmento ya no está agregado, no se toca la vista.
                        if (!isAdded()) {
                            return;
                        }

                        // set contact adapter
                        if(!calls.isEmpty()){

                            adaptador = new log_adapter(requireContext().getApplicationContext(),R.layout.log_event_listitem,calls);
                            lstOpciones.setAdapter(adaptador);
                            noempty.setVisibility(View.VISIBLE);
                            empty.setVisibility(View.GONE);

                        }else{

                            noempty.setVisibility(View.GONE);
                            empty.setVisibility(View.VISIBLE);
                            // Sin emparejar no llega nada a paynani: se dice por qué (#122).
                            TextView why = empty.findViewById(R.id.contac_phone);
                            if (why != null) why.setText(new SessionManager(getActivity().getApplicationContext()).isLoggedIn()
                                    ? R.string.no_log_records_found : R.string.log_unpaired);
                        }

                        // set the progress to GONE
                        progressLayout.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_process) {


            // Barrido de la bandeja (APP-4): lo que llegó desde la última marca y
            // no vio el receptor. Encolar no es enviar: el registro anota
            // «enviado a paynani» cuando llega el ack.
            // Fuera del hilo principal: el barrido usa la cola (#105).
            final Context app = getActivity().getApplicationContext();
            GatewayQueue.run(() -> {
                final int n = Inbound.sweep(app);
                mainHandler.post(() -> {
                    contador = n;
                    if (isAdded()) {
                        AlertDialog.Builder alertDialog = new AlertDialog.Builder(getActivity());
                        alertDialog.setTitle(getResources().getString(R.string.generic_title));
                        alertDialog.setMessage(getString(R.string.total_msjs) + " " + contador);
                        alertDialog.setPositiveButton(getString(R.string.ok), null);
                        alertDialog.show();
                    }
                });
            });
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_fragment_log, menu);



        super.onCreateOptionsMenu(menu,inflater);
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.activity_event_log, container, false);

        setHasOptionsMenu(true);

        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(getActivity()));

        empty = (RelativeLayout) v.findViewById(R.id.view_emptycall);
        noempty = (RelativeLayout) v.findViewById(R.id.list_noempty);
        eventLog = new EventLogRepository(getActivity().getApplicationContext());
        session = new SessionManager(getActivity().getApplicationContext());

        progressLayout = (RelativeLayout) v.findViewById(R.id.progress_layout);

        loginPreferences = getActivity().getSharedPreferences("loginPrefs_", Context.MODE_PRIVATE);
        loginPrefsEditor = loginPreferences.edit();

        IntentFilter intentFilter = new IntentFilter("com.iaaorgmx.paynani.UPDATELOG");

        mHandleMessageReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {

                L.e("BROADCAST  UPDATELOG", "RECEIVED");


                if(isAdded()){
                    try{


                        loadLog();








                    }catch (Exception e){
                        L.e("isNoAdded fragment","e " + e.getMessage());
                    }

                }else{

                    L.e("isNoAdded fragment","error fragment");
                }



            }
        };

        int flags = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            flags = Context.RECEIVER_NOT_EXPORTED;
        }

        androidx.core.content.ContextCompat.registerReceiver(
                getActivity().getApplicationContext(),
                mHandleMessageReceiver,
                intentFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        );

        lstOpciones = (ListView) v.findViewById(R.id.LstOpciones);
        lstOpciones_remote = (ListView) v.findViewById(R.id.LstOpciones_remote);

        lstOpciones_remote.setVisibility(View.GONE);
        lstOpciones.setVisibility(View.VISIBLE);




        loadLog();

        lstOpciones.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                LogEvent_Model item = (LogEvent_Model) parent.getAdapter().getItem(position);


                // El detalle en una hoja de Material3 (#113), con el mismo título e ícono de la lista.
                if (item != null && item.getEvent_name() != null) {
                    EventDetailSheet.show(EventLogFragment.this, item, who(item));
                }



            }
        });


        return v;
    }


    public ArrayList<LogEvent_Model> getcalls(){

        LogEvent_Model call_item;
        LinkedHashMap<String, LogEvent_Model> call_history = new LinkedHashMap<String, LogEvent_Model>();

        List<EventLog> rows = eventLog.lastWeek();

        L.e("Total entries", "" + rows.size() + " records");

        for (EventLog row : rows) {

            long call_id = row.id;
            String phNumber = row.phone;
            L.e("--->","El numero es: " + phNumber);
            String timestamp = row.timestamp;
            String event_name = row.evento;
            L.e("--->","El evento es: " + event_name);
            String msg = row.msg;

            call_item = new LogEvent_Model();

            if(!event_name.equals("")){

                call_item.setEvent_name(event_name);
                call_item.setdate(timestamp);
                // Siempre, no sólo con teléfono: los avisos de la pasarela
                // (gateway_rejected, gateway_revoked) no traen número (#65).
                call_item.setMsg(msg);

                String c_name = "";
                String c_photo = "";

                if(phNumber != null){

                    if(!phNumber.equals("")){

                        if(event_name.equals(EventLogRepository.EVENT_SENT_TO_SERVER)){

                            L.e("PHONEVALUE",event_name +" - val: " + phNumber);
                            call_item.setphone(phNumber);

                        }else{


                            call_item.setMsg(msg);

                            call_item.setphone(phNumber);
                            // APP-7: sin permiso de contactos; el registro muestra el número.
                            call_item.setphotoContact("default");
                            call_item.setcontact_name(phNumber);



                        }


                    }

                }





                call_item.setdate(timestamp.toString());
                call_history.put(String.valueOf(call_id), call_item);

            }else{
                continue;
            }
        }

        return new ArrayList<LogEvent_Model>(call_history.values());

    }

    public class log_adapter extends ArrayAdapter<LogEvent_Model> {


        public int calculateDays(java.util.Date date1, java.util.Date today){

            long diff = today.getTime() - date1.getTime();

            int diffDays = (int) diff / (1000 * 60 * 60 * 24);

            return diffDays;

        }

        public String formatDate(String timestamp){

            String dat = "";
            String tim = "";

            java.util.Date date1 = null;
            java.util.Date full_date = null;
            java.util.Date today = null;
            //

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            SimpleDateFormat df2 = new SimpleDateFormat("yyyy-MM-dd");
            Calendar calobj = Calendar.getInstance();
            String st_today = df2.format(calobj.getTime());

            try {

                full_date = df.parse(timestamp);

                df2.applyPattern("yyyy-MM-dd");
                date1 = df2.parse(timestamp);
                today = df2.parse(st_today);

                int numofdays = calculateDays(date1,today);

                switch (numofdays){

                    case 0:
                        dat = getString(R.string.label_today);
                        break;
                    case 1:
                        dat = getString(R.string.label_yesterday);
                        break;
                    default:
                        df.applyPattern(getString(R.string.label_format_date));
                        dat = df.format(date1);
                        break;
                }


                df.applyPattern("h:mm a");
                tim = df.format(full_date);

            } catch (ParseException e) {
                L.e("Format Error", e.getMessage());
            }


            return tim + "\n" +dat;
        }


        public long StringToMillis(String string_date){

            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yy HH:mm");
            String dateInString = string_date;
            Date date = null;
            try {
                date = sdf.parse(dateInString);
            } catch (ParseException e) {
                // TODO Auto-generated catch block
                //e.printStackTrace();
            }

            return date.getTime();
        }

        Activity context;



        log_adapter(Context context, int textViewResourceId, ArrayList<LogEvent_Model> items) {

            // Una sola lista, la de ArrayAdapter: getCount() y getView() no se
            // pueden desfasar (#100).
            super(context, textViewResourceId, items);


        }

        public View getView(int position, View convertView, ViewGroup parent) {

            View view = convertView;

            if (view == null) {

                LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = inflater.inflate(R.layout.log_event_listitem, null);
            }



            final LogEvent_Model call_details = getItem(position);

            if(call_details != null && call_details.getEvent_name() != null){

                ImageView thumb = (ImageView) view.findViewById(R.id.sponsor_img);

                TextView tv_name = (TextView) view.findViewById(R.id.tv_phone);

                TextView tv_phone = (TextView) view.findViewById(R.id.tv_description);

                TextView tv_date = (TextView) view.findViewById(R.id.tv_date);

                // yyyy-MM-dd HH:mm:ss
                tv_date.setText(formatDate(call_details.getdate()));
                //tv_date.setText(formatDate("2016-09-25 09:27:30"));



                // Un solo estilo para todos (#65): glifo de Material Symbols en grana
                // sobre un círculo de papel; los errores, en tinta. Con foto del
                // contacto, se queda la foto.
                String event = call_details.getEvent_name();
                int title = EventKind.title(event);
                if (title != 0) tv_name.setText(title);
                else tv_name.setText(event);
                int icon = EventKind.icon(event);
                switch (event){
                    case "rcs_unseen":
                        tv_phone.setText(getString(R.string.text_rcs_unseen_from, call_details.getcontactname()));
                        break;
                    case "sent_server":
                        tv_phone.setText(getString(R.string.delivered_sms_from, who(call_details)));
                        break;
                    case "sent_server_answered":
                        tv_phone.setText(getString(R.string.delivered_answered_from, who(call_details)));
                        break;
                    case "sent_server_incoming":
                        tv_phone.setText(getString(R.string.delivered_missed_from, who(call_details)));
                        break;
                    case "sent_server_outgoing":
                        tv_phone.setText(getString(R.string.delivered_outgoing_to, who(call_details)));
                        break;
                    case "reply_contact":
                        tv_phone.setText(getString(R.string.default_response_sent_to, who(call_details)));
                        break;
                    case "call_answered":
                        tv_phone.setText(getString(R.string.call_answered_received_from, who(call_details)));
                        break;
                    case "call_not_answered":
                        tv_phone.setText(getString(R.string.call_notanswered_received_from, who(call_details)));
                        break;
                    case "call_outgoing":
                        tv_phone.setText(getString(R.string.call_outgoing_call_to, who(call_details)));
                        break;
                    case "incoming_sms":
                        tv_phone.setText(getString(R.string.text_message_received_from, who(call_details)));
                        break;
                    case "gateway_revoked":
                        // Del recurso, no del msg guardado: las filas viejas lo traen
                        // en español fijo (#102).
                        tv_phone.setText(R.string.log_gateway_revoked_detail);
                        break;
                    default:
                        // gateway_rejected y los eventos que esta versión no conoce.
                        tv_phone.setText(call_details.getMsg());
                        break;
                }
                // Con foto del contacto, se queda la foto (#65).
                switch (event) {
                    case "call_answered":
                    case "call_not_answered":
                    case "call_outgoing":
                    case "incoming_sms":
                        photoOr(thumb, call_details, icon);
                        break;
                    default:
                        thumb.setImageResource(icon);
                        break;
                }


            }




            return(view);
        }


    }

    /** El número de la fila, o «número oculto» si no lo hay (#67). */
    private String who(LogEvent_Model details) {
        String n = details.getcontactname();
        if (n == null || n.isEmpty()) n = details.getphonenumber();
        return n == null || n.isEmpty() ? getString(R.string.status_hidden) : n;
    }

    /** La foto del contacto si la hay; si no, el ícono del evento (#65). */
    private static void photoOr(ImageView thumb, LogEvent_Model details, int icon) {
        String photo = details.getPhotoContact();
        if (photo != null && !photo.isEmpty() && !"default".equals(photo)) {
            thumb.setImageURI(Uri.parse(photo));
            if (thumb.getDrawable() != null) return;
        }
        thumb.setImageResource(icon);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logExecutor.shutdownNow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHandleMessageReceiver != null) {
            try {
                getActivity().getApplicationContext().unregisterReceiver(mHandleMessageReceiver);
            } catch (Exception e) {
            }
        }
    }


}
