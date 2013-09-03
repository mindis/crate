package crate.elasticsearch;

import crate.elasticsearch.blob.PutChunkReplicaRequest;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.UUID;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SerializationTests {


    @Test
    public void testPutChunkReplicaRequestSerialization() throws Exception {
        BytesStreamOutput outputStream = new BytesStreamOutput();

        UUID transferId = UUID.randomUUID();

        PutChunkReplicaRequest requestOut = new PutChunkReplicaRequest();
        requestOut.transferId = transferId;
        requestOut.currentPos = 10;
        requestOut.isLast = false;
        requestOut.content = new BytesArray(new byte[] { 0x65, 0x66 });
        requestOut.sourceNodeId = "nodeId";

        requestOut.writeTo(outputStream);
        BytesStreamInput inputStream = new BytesStreamInput(outputStream.bytes().copyBytesArray());

        PutChunkReplicaRequest requestIn = new PutChunkReplicaRequest();
        requestIn.readFrom(inputStream);

        assertEquals(requestOut.currentPos, requestIn.currentPos);
        assertEquals(requestOut.isLast, requestIn.isLast);
        assertEquals(requestOut.content, requestIn.content);
        assertEquals(requestOut.transferId, requestIn.transferId);
    }
}
