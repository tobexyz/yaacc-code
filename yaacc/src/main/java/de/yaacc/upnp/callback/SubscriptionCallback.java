/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.callback;

import android.content.Context;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.UserConstants;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.GENASubscription;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.meta.Service;

import java.util.Collections;
import java.util.concurrent.ExecutorService;

import de.yaacc.upnp.protocol.sync.SendingSubscribe;
import de.yaacc.upnp.protocol.sync.SendingUnsubscribe;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.Exceptions;
import de.yaacc.util.InterfaceResolutionHelper;
import de.yaacc.util.YaaccLogger;

public abstract class SubscriptionCallback implements Runnable {

    protected final Service service;
    protected final Integer requestedDurationSeconds;
    protected final Registry registry;
    protected final HttpRequestSender httpRequestSender;
    protected final ExecutorService executorService;
    protected final Context context;

    private GENASubscription subscription;

    protected SubscriptionCallback(Service service, Registry registry, HttpRequestSender httpRequestSender, ExecutorService executorService, Context context) {
        this.service = service;
        this.requestedDurationSeconds = UserConstants.DEFAULT_SUBSCRIPTION_DURATION_SECONDS;
        this.registry = registry;
        this.httpRequestSender = httpRequestSender;
        this.executorService = executorService;
        this.context = context;
    }

    protected SubscriptionCallback(Service service, int requestedDurationSeconds, Registry registry, HttpRequestSender httpRequestSender, ExecutorService executorService, Context context) {
        this.service = service;
        this.requestedDurationSeconds = requestedDurationSeconds;
        this.registry = registry;
        this.httpRequestSender = httpRequestSender;
        this.executorService = executorService;
        this.context = context;
    }

    public static String createDefaultFailureMessage(UpnpResponse responseStatus, Exception exception) {
        String message = "Subscription failed: ";
        if (responseStatus != null) {
            message = message + " HTTP response was: " + responseStatus.getResponseDetails();
        } else if (exception != null) {
            message = message + " Exception occured: " + exception;
        } else {
            message = message + " No response received.";
        }
        return message;
    }

    public Service getService() {
        return service;
    }

    synchronized public GENASubscription getSubscription() {
        return subscription;
    }

    synchronized public void setSubscription(GENASubscription subscription) {
        this.subscription = subscription;
    }

    synchronized public void run() {
        if (getService() instanceof LocalService) {
            establishLocalSubscription((LocalService) service);
        } else if (getService() instanceof RemoteService) {
            establishRemoteSubscription((RemoteService) service);
        }
    }

    private void establishLocalSubscription(LocalService service) {
        if (registry.getLocalDevice(service.getDevice().getIdentity().getUdn(), false) == null) {
            YaaccLogger.v(getClass().getName(), "Local device service is currently not registered, failing subscription immediately");
            failed(null, null, new IllegalStateException("Local device is not registered"));
            return;
        }

        LocalGENASubscription localSubscription = null;
        try {
            localSubscription = new LocalGENASubscription(service, Integer.MAX_VALUE, Collections.EMPTY_LIST) {
                public void failed(Exception ex) {
                    synchronized (SubscriptionCallback.this) {
                        SubscriptionCallback.this.setSubscription(null);
                        SubscriptionCallback.this.failed(null, null, ex);
                    }
                }

                public void established() {
                    synchronized (SubscriptionCallback.this) {
                        SubscriptionCallback.this.setSubscription(this);
                        SubscriptionCallback.this.established(this);
                    }
                }

                public void ended(CancelReason reason) {
                    synchronized (SubscriptionCallback.this) {
                        SubscriptionCallback.this.setSubscription(null);
                        SubscriptionCallback.this.ended(this, reason, null);
                    }
                }

                public void eventReceived() {
                    synchronized (SubscriptionCallback.this) {
                        SubscriptionCallback.this.eventReceived(this);
                        incrementSequence();
                    }
                }
            };

            registry.addLocalSubscription(localSubscription);
            localSubscription.establish();
            eventReceived(localSubscription);
            localSubscription.incrementSequence();
            localSubscription.registerOnService();

        } catch (Exception ex) {
            YaaccLogger.v(getClass().getName(), "Local callback creation failed: " + ex.toString());
            YaaccLogger.v(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));
            if (localSubscription != null)
                registry.removeLocalSubscription(localSubscription);
            failed(localSubscription, null, ex);
        }
    }

    private void establishRemoteSubscription(RemoteService service) {
        RemoteGENASubscription remoteSubscription = new RemoteGENASubscription(service, requestedDurationSeconds) {
            public void failed(UpnpResponse responseStatus) {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.setSubscription(null);
                    SubscriptionCallback.this.failed(this, responseStatus, null);
                }
            }

            public void established() {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.setSubscription(this);
                    SubscriptionCallback.this.established(this);
                }
            }

            public void ended(CancelReason reason, UpnpResponse responseStatus) {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.setSubscription(null);
                    SubscriptionCallback.this.ended(this, reason, responseStatus);
                }
            }

            public void eventReceived() {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.eventReceived(this);
                }
            }

            public void eventsMissed(int numberOfMissedEvents) {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.eventsMissed(this, numberOfMissedEvents);
                }
            }

            public void invalidMessage(UnsupportedDataException ex) {
                synchronized (SubscriptionCallback.this) {
                    SubscriptionCallback.this.invalidMessage(this, ex);
                }
            }
        };

        SendingSubscribe protocol = new SendingSubscribe(
                registry,
                httpRequestSender,
                remoteSubscription,
                InterfaceResolutionHelper.getNetworkAddress(context)
        );
        protocol.run();
    }

    synchronized public void end() {
        if (subscription == null) return;
        if (subscription instanceof LocalGENASubscription) {
            endLocalSubscription((LocalGENASubscription) subscription);
        } else if (subscription instanceof RemoteGENASubscription) {
            endRemoteSubscription((RemoteGENASubscription) subscription);
        }
    }

    private void endLocalSubscription(LocalGENASubscription subscription) {
        registry.removeLocalSubscription(subscription);
        subscription.end(null);
    }

    private void endRemoteSubscription(RemoteGENASubscription subscription) {
        executorService.execute(new SendingUnsubscribe(registry, httpRequestSender, subscription));
    }

    protected void failed(GENASubscription subscription, UpnpResponse responseStatus, Exception exception) {
        failed(subscription, responseStatus, exception, createDefaultFailureMessage(responseStatus, exception));
    }

    protected abstract void failed(GENASubscription subscription, UpnpResponse responseStatus, Exception exception, String defaultMsg);

    protected abstract void established(GENASubscription subscription);

    protected abstract void ended(GENASubscription subscription, CancelReason reason, UpnpResponse responseStatus);

    protected abstract void eventReceived(GENASubscription subscription);

    protected void eventsMissed(GENASubscription subscription, int numberOfMissedEvents) {
    }

    protected void invalidMessage(GENASubscription subscription, UnsupportedDataException ex) {
    }

    @Override
    public String toString() {
        return "(SubscriptionCallback) " + getService();
    }
}
