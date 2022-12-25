package org.fourthline.cling.support.test.model;


import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.DIDLObject;
import org.junit.Test;

public class DIDLObjectTest {

    @Test
    public void testHasPropertyWithNormalClass() {
        DIDLObject didlObject = new DIDLObject() {

        };
        DIDLObject.Property property = new DIDLObject.Property.UPNP.ACTOR();
        didlObject.addProperty(property);
        assertTrue(didlObject.hasProperty(DIDLObject.Property.UPNP.ACTOR.class));
    }

    @Test
    public void testHasPropertyWithAnonymousClass() {
        DIDLObject didlObject = new DIDLObject() {

        };
        DIDLObject.Property property = new DIDLObject.Property.UPNP.ACTOR() {

        };
        didlObject.addProperty(property);
        assertTrue(didlObject.hasProperty(DIDLObject.Property.UPNP.ACTOR.class));
    }

}
