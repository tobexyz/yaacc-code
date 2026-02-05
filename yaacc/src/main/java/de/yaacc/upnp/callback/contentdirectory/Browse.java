package de.yaacc.upnp.callback.contentdirectory;

import android.util.Log;

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.BrowseResult;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.SortCriterion;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class Browse extends ActionCallback {

    public static final String CAPS_WILDCARD = "*";

    public enum Status {
        NO_CONTENT("No Content"),
        LOADING("Loading..."),
        OK("OK");

        private String defaultMessage;

        Status(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    public Browse(Service service, String objectID, BrowseFlag flag, HttpRequestSender httpRequestSender) {
        this(service, objectID, flag, CAPS_WILDCARD, 0, null, httpRequestSender);
    }

    public Browse(Service service, String objectID, BrowseFlag flag,
                  String filter, long firstResult, Long maxResults, HttpRequestSender httpRequestSender, SortCriterion... orderBy) {

        super(new ActionInvocation(service.getAction("Browse")), httpRequestSender);

        Log.v(getClass().getName(), "Creating browse action for object ID: " + objectID);

        getActionInvocation().setInput("ObjectID", objectID);
        getActionInvocation().setInput("BrowseFlag", flag.toString());
        getActionInvocation().setInput("Filter", filter);
        getActionInvocation().setInput("StartingIndex", new UnsignedIntegerFourBytes(firstResult));
        getActionInvocation().setInput("RequestedCount",
                new UnsignedIntegerFourBytes(maxResults == null ? getDefaultMaxResults() : maxResults)
        );
        getActionInvocation().setInput("SortCriteria", SortCriterion.toString(orderBy));
    }

    @Override
    public void run() {
        updateStatus(Status.LOADING);
        super.run();
    }

    @Override
    public void success(ActionInvocation invocation) {
        Log.v(getClass().getName(), "Successful browse action, reading output argument values");

        BrowseResult result = new BrowseResult(
                invocation.getOutput("Result").getValue().toString(),
                (UnsignedIntegerFourBytes) invocation.getOutput("NumberReturned").getValue(),
                (UnsignedIntegerFourBytes) invocation.getOutput("TotalMatches").getValue(),
                (UnsignedIntegerFourBytes) invocation.getOutput("UpdateID").getValue()
        );

        boolean proceed = receivedRaw(invocation, result);

        if (proceed && result.getCountLong() > 0 && result.getResult().length() > 0) {

            try {

                DIDLParser didlParser = new DIDLParser();
                DIDLContent didl = didlParser.parse(result.getResult());
                received(invocation, didl);
                updateStatus(Status.OK);

            } catch (Exception ex) {
                invocation.setFailure(
                        new ActionException(ErrorCode.ACTION_FAILED, "Can't parse DIDL XML response: " + ex, ex)
                );
                failure(invocation, null);
            }

        } else {
            received(invocation, new DIDLContent());
            updateStatus(Status.NO_CONTENT);
        }
    }

    public long getDefaultMaxResults() {
        return 999;
    }

    public boolean receivedRaw(ActionInvocation actionInvocation, BrowseResult browseResult) {
        return true;
    }

    public abstract void received(ActionInvocation actionInvocation, DIDLContent didl);

    public abstract void updateStatus(Status status);

}
