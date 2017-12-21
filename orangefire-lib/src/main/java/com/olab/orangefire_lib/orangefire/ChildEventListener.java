package com.olab.orangefire_lib.orangefire;

import com.orange.webcom.sdk.DataSnapshot;

public interface ChildEventListener {

    void onChildAdded(DataSnapshot dataSnapshot, String s);
    void onChildChanged(DataSnapshot dataSnapshot, String s);
    void onChildRemoved(DataSnapshot dataSnapshot);

}
