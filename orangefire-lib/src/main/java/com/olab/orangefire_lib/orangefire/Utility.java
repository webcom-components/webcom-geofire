package com.olab.orangefire_lib.orangefire;

import android.support.annotation.Nullable;

import com.orange.webcom.sdk.DataSnapshot;
import com.orange.webcom.sdk.OnQuery;
import com.orange.webcom.sdk.Query;
import com.orange.webcom.sdk.WebcomError;
import com.orange.webcom.sdk.WebcomException;

public class Utility {

    public interface ValueEventListener {

        void onDataChange(DataSnapshot dataSnapshot);

        void onCancelled(WebcomError webcomError);
    }

    public static void AddListenerForSingleValueEvent(final Query webcom, final ValueEventListener locationCallback){

        OnQuery myQuery = new OnQuery() {
            @Override
            public void onComplete(DataSnapshot dataSnapshot, @Nullable String s) {
                locationCallback.onDataChange( dataSnapshot );
            }

            @Override
            public void onCancel(WebcomError webcomError) {
                locationCallback.onCancelled( webcomError);
            }

            @Override
            public void onError(WebcomError webcomError) {
            }
        };

        try {
            webcom.once(Query.Event.VALUE, myQuery);
        } catch (WebcomException e) {
            e.printStackTrace();
        }
    }
}
