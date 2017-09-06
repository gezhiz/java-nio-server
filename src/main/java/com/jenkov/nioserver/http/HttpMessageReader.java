package com.jenkov.nioserver.http;

import com.jenkov.nioserver.IMessageReader;
import com.jenkov.nioserver.Message;
import com.jenkov.nioserver.MessageBuffer;
import com.jenkov.nioserver.Socket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jjenkov on 18-10-2015.
 */
public class HttpMessageReader implements IMessageReader {

    private MessageBuffer messageBuffer    = null;

    private List<Message> completeMessages = new ArrayList<Message>();//完成读取的消息列表
    private Message       nextMessage      = null;

    public HttpMessageReader() {
    }

    @Override
    public void init(MessageBuffer readMessageBuffer) {
        this.messageBuffer        = readMessageBuffer;
        this.nextMessage          = messageBuffer.getMessage();
        this.nextMessage.metaData = new HttpHeaders();
    }

    @Override
    public void read(Socket socket, ByteBuffer byteBuffer) throws IOException {
        int bytesRead = socket.read(byteBuffer);//如果读取完成所有的数据，则socket会被标记已经读完数据
        byteBuffer.flip();

        if(byteBuffer.remaining() == 0){
            byteBuffer.clear();
            return;
        }

        this.nextMessage.writeToMessage(byteBuffer);

        int endIndex = HttpUtil.parseHttpRequest(this.nextMessage.sharedArray, this.nextMessage.offset, this.nextMessage.offset + this.nextMessage.length, (HttpHeaders) this.nextMessage.metaData);
        if(endIndex != -1){
            //找到了消息的结束标志，可以添加一条消息
            Message message = this.messageBuffer.getMessage();
            message.metaData = new HttpHeaders();

            message.writePartialMessageToMessage(nextMessage, endIndex);

            completeMessages.add(nextMessage);
            nextMessage = message;
        }
        byteBuffer.clear();
    }


    @Override
    public List<Message> getMessages() {
        return this.completeMessages;
    }

}
