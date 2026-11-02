package com.iaaorgmx.paynani;
//PaynaniApp
public class LogEvent_Model
{
    private int id;
    private String contact_name;
    private String phonenumber;
    private String type;
    private String label;
    private String date;
    private String photoUri;
    private String event_name;

    private String msg;


    // Nuevos campos

    private String callUnique_id;
    private String iso2;
    private String duration;
    private String call_cost_carrier;
    private String saving_call;


    public LogEvent_Model(){

        ;
    }

    //-------------------------

    public String getMsg(){
        return this.msg;
    }

    public void setMsg(String msg){

        this.msg = msg;
    }

    public String getEvent_name(){
        return this.event_name;
    }

    public void setEvent_name(String event){

        this.event_name = event;
    }

    public String getLabel(){
        return this.label;
    }

    public void setLabel(String label){

        this.label = label;
    }


    public String getISO2(){
        return this.iso2;
    }

    public void setCall_iso2(String iso){

        this.iso2 = iso;
    }

    public void setCarrierCost(String carrier){

        this.call_cost_carrier = carrier;
    }

    public String getCarrierCost(){
        return this.call_cost_carrier;
    }


    public void setSaving(String saving){

        this.saving_call = saving;
    }

    public String getSaving(){

        return this.saving_call;
    }

    public void setDuration(String duration){

        this.duration = duration;
    }

    public String getDuration(){

        return this.duration;
    }

    public void setUniqueID(String id){

        this.callUnique_id = id;
    }

    public String getCallID(){

        return this.callUnique_id;
    }


    //-------------------------

    public int getCall_ID(){
        return this.id;
    }

    public void setCall_ID(int call_id){

        this.id = call_id;
    }

    public String getPhotoContact(){
        return this.photoUri;
    }

    public String getphonenumber(){
        return this.phonenumber;
    }

    public String getcontactname(){
        return this.contact_name;
    }

    public String gettype(){
        return this.type;
    }

    public String getduration(){
        return this.duration;
    }

    public String getdate(){
        return this.date;
    }


    public void setphotoContact(String photouri){
        this.photoUri = photouri;
    }

    public void setphone(String phone){
        this.phonenumber = phone;
    }

    public void setcontact_name(String call_contact){
        this.contact_name = call_contact;
    }

    public void settype(String call_type){
        this.type = call_type;
    }

    public void setduration(String call_duration){
        this.duration = call_duration;
    }

    public void setdate(String call_date){
        this.date = call_date;
    }

}

