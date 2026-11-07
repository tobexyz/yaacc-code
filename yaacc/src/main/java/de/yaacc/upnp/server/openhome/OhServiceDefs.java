/*
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
package de.yaacc.upnp.server.openhome;

/**
 * The OpenHome services YAACC's renderer offers. The versions are the lowest ones of the OpenHome service descriptions that a
 * control point of any generation can use: a device that offers a higher version answers a request for a lower one, so the
 * lowest version with the actions of the later ones left out (Playlist 1, Info 1, Time 1, Product 2, Volume 2).
 */
public final class OhServiceDefs {
    private OhServiceDefs() {
    }

    public static OhServiceDef playlist() {
        return new OhServiceDef("Playlist", 1).vars(
                "TransportState:string*[Playing|Paused|Stopped|Buffering]", "Repeat:boolean*", "Shuffle:boolean*", "Id:ui4*",
                "IdArray:bin.base64*", "TracksMax:ui4*", "ProtocolInfo:string*", "Index:ui4", "Relative:i4", "Absolute:ui4",
                "IdList:string", "TrackList:string", "Uri:string", "Metadata:string", "IdArrayToken:ui4", "IdArrayChanged:boolean"
        ).actions(
                "Play", "Pause", "Stop", "Next", "Previous", "SetRepeat >Value=Repeat", "Repeat <Value=Repeat",
                "SetShuffle >Value=Shuffle", "Shuffle <Value=Shuffle", "SeekSecondAbsolute >Value=Absolute",
                "SeekSecondRelative >Value=Relative", "SeekId >Value=Id", "SeekIndex >Value=Index",
                "TransportState <Value=TransportState", "Id <Value=Id", "Read >Id=Id <Uri=Uri <Metadata=Metadata",
                "ReadList >IdList=IdList <TrackList=TrackList", "Insert >AfterId=Id >Uri=Uri >Metadata=Metadata <NewId=Id",
                "DeleteId >Value=Id", "DeleteAll", "TracksMax <Value=TracksMax", "IdArray <Token=IdArrayToken <Array=IdArray",
                "IdArrayChanged >Token=IdArrayToken <Value=IdArrayChanged", "ProtocolInfo <Value=ProtocolInfo");
    }

    public static OhServiceDef info() {
        return new OhServiceDef("Info", 1).vars(
                "TrackCount:ui4*", "DetailsCount:ui4*", "MetatextCount:ui4*", "Uri:string*", "Metadata:string*", "Duration:ui4*",
                "BitRate:ui4*", "BitDepth:ui4*", "SampleRate:ui4*", "Lossless:boolean*", "CodecName:string*", "Metatext:string*"
        ).actions(
                "Counters <TrackCount=TrackCount <DetailsCount=DetailsCount <MetatextCount=MetatextCount",
                "Track <Uri=Uri <Metadata=Metadata",
                "Details <Duration=Duration <BitRate=BitRate <BitDepth=BitDepth <SampleRate=SampleRate <Lossless=Lossless <CodecName=CodecName",
                "Metatext <Value=Metatext");
    }

    public static OhServiceDef time() {
        return new OhServiceDef("Time", 1).vars("TrackCount:ui4*", "Duration:ui4*", "Seconds:ui4*")
                .actions("Time <TrackCount=TrackCount <Duration=Duration <Seconds=Seconds");
    }

    public static OhServiceDef volume() {
        return new OhServiceDef("Volume", 2).vars(
                "Volume:ui4*", "Mute:boolean*", "Balance:i4*", "Fade:i4*", "VolumeLimit:ui4*", "VolumeMax:ui4*", "VolumeUnity:ui4*",
                "VolumeSteps:ui4*", "VolumeMilliDbPerStep:ui4*", "BalanceMax:ui4*", "FadeMax:ui4*", "UnityGain:boolean*"
        ).actions(
                "Characteristics <VolumeMax=VolumeMax <VolumeUnity=VolumeUnity <VolumeSteps=VolumeSteps "
                        + "<VolumeMilliDbPerStep=VolumeMilliDbPerStep <BalanceMax=BalanceMax <FadeMax=FadeMax",
                "SetVolume >Value=Volume", "VolumeInc", "VolumeDec", "Volume <Value=Volume", "SetBalance >Value=Balance",
                "BalanceInc", "BalanceDec", "Balance <Value=Balance", "SetFade >Value=Fade", "FadeInc", "FadeDec", "Fade <Value=Fade",
                "SetMute >Value=Mute", "Mute <Value=Mute", "VolumeLimit <Value=VolumeLimit", "UnityGain <Value=UnityGain");
    }

    public static OhServiceDef product() {
        return new OhServiceDef("Product", 2).vars(
                "ManufacturerName:string*", "ManufacturerInfo:string*", "ManufacturerUrl:string*", "ManufacturerImageUri:string*",
                "ModelName:string*", "ModelInfo:string*", "ModelUrl:string*", "ModelImageUri:string*", "ProductRoom:string*",
                "ProductName:string*", "ProductInfo:string*", "ProductUrl:string*", "ProductImageUri:string*", "Standby:boolean*",
                "SourceIndex:ui4*", "SourceCount:ui4*", "SourceXml:string*", "Attributes:string*", "SourceXmlChangeCount:ui4",
                "SourceType:string", "SourceName:string", "SourceSystemName:string", "SourceVisible:boolean"
        ).actions(
                "Manufacturer <Name=ManufacturerName <Info=ManufacturerInfo <Url=ManufacturerUrl <ImageUri=ManufacturerImageUri",
                "Model <Name=ModelName <Info=ModelInfo <Url=ModelUrl <ImageUri=ModelImageUri",
                "Product <Room=ProductRoom <Name=ProductName <Info=ProductInfo <Url=ProductUrl <ImageUri=ProductImageUri",
                "Standby <Value=Standby", "SetStandby >Value=Standby", "SourceCount <Value=SourceCount", "SourceXml <Value=SourceXml",
                "SourceIndex <Value=SourceIndex", "SetSourceIndex >Value=SourceIndex", "SetSourceIndexByName >Value=SourceName",
                "SetSourceBySystemName >Value=SourceSystemName",
                "Source >Index=SourceIndex <SystemName=SourceName <Type=SourceType <Name=SourceName <Visible=SourceVisible",
                "Attributes <Value=Attributes", "SourceXmlChangeCount <Value=SourceXmlChangeCount");
    }

    /**
     * A one-channel radio. YAACC has no radio source; the service is there for controllers that start a single stream with it
     * (Home Assistant's play_media does): the channel becomes the queue of the playlist (see {@link OhRendererCore}).
     */
    public static OhServiceDef radio() {
        return new OhServiceDef("Radio", 1).vars(
                "Uri:string*", "Metadata:string*", "TransportState:string*[Stopped|Playing|Paused|Buffering]", "Id:ui4*",
                "IdArray:bin.base64*", "ChannelsMax:ui4*", "ProtocolInfo:string*", "Relative:i4", "Absolute:ui4",
                "IdArrayToken:ui4", "IdArrayChanged:boolean", "IdList:string", "ChannelList:string"
        ).actions(
                "Play", "Pause", "Stop", "SeekSecondAbsolute >Value=Absolute", "SeekSecondRelative >Value=Relative",
                "Channel <Uri=Uri <Metadata=Metadata", "SetChannel >Uri=Uri >Metadata=Metadata",
                "TransportState <Value=TransportState", "Id <Value=Id", "SetId >Value=Id >Uri=Uri",
                "Read >Id=Id <Metadata=Metadata", "ReadList >IdList=IdList <ChannelList=ChannelList",
                "IdArray <Token=IdArrayToken <Array=IdArray", "IdArrayChanged >Token=IdArrayToken <Value=IdArrayChanged",
                "ChannelsMax <Value=ChannelsMax", "ProtocolInfo <Value=ProtocolInfo");
    }
}
