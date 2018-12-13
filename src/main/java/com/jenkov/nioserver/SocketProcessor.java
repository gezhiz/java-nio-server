package com.jenkov.nioserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;

/**
 * Created by jjenkov on 16-10-2015.
 */
public class SocketProcessor implements Runnable {

    private Queue<Socket>  inboundSocketQueue   = null;

    private MessageBuffer  readMessageBuffer    = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets
    private MessageBuffer  writeMessageBuffer   = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets (space for more to write?)

    private IMessageReaderFactory messageReaderFactory = null;

    private Queue<Message> outboundMessageQueue = new LinkedList<>(); //todo use a better / faster queue.

    private Map<Long, Socket> socketMap         = new HashMap<>();

    private ByteBuffer readByteBuffer  = ByteBuffer.allocate(1024 * 1024);
    private ByteBuffer writeByteBuffer = ByteBuffer.allocate(1024 * 1024);
    private Selector   readSelector    = null;
    private Selector   writeSelector   = null;

    private IMessageProcessor messageProcessor = null;
    private WriteProxy        writeProxy       = null;

    private long              nextSocketId = 16 * 1024; //start incoming socket ids from 16K - reserve bottom ids for pre-defined sockets (servers).

    private Set<Socket> emptySockets = new HashSet<>();//未写入数据的socket列表，需要注册OP_WRITE事件
    private Set<Socket> nonEmptySockets = new HashSet<>();//写完成的队列


    public SocketProcessor(Queue<Socket> inboundSocketQueue, MessageBuffer readMessageBuffer,
                           MessageBuffer writeMessageBuffer, IMessageReaderFactory messageReaderFactory,
                           IMessageProcessor messageProcessor) throws IOException {
        this.inboundSocketQueue = inboundSocketQueue;

        this.readMessageBuffer    = readMessageBuffer;
        this.writeMessageBuffer   = writeMessageBuffer;
        this.writeProxy           = new WriteProxy(writeMessageBuffer, this.outboundMessageQueue);

        this.messageReaderFactory = messageReaderFactory;

        this.messageProcessor     = messageProcessor;

        this.readSelector         = Selector.open();
        this.writeSelector        = Selector.open();
    }

    public void run() {
        while(true){
            try{
                executeCycle();
            } catch(IOException e){
                e.printStackTrace();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public void executeCycle() throws IOException {
        takeNewSockets();//初始化socket，注册OP_READ事件
        readFromSockets();//实时获取"读就绪"的socket，进行readByteBuffer指定长度的读
        writeToSockets();//向socket写入数据
    }


    /**
     * 取出队列列所有的socket
     * 初始化socket
     * 注册OP_READ事件
     * @throws IOException
     */
    public void takeNewSockets() throws IOException {
        Socket newSocket = this.inboundSocketQueue.poll();//拿到队列头部的socket

        //循环的读取所有新来的socket，并注册商可读（OP_READ）事件
        while(newSocket != null){
            newSocket.socketId = this.nextSocketId++;
            newSocket.socketChannel.configureBlocking(false);//使用nio模式：非阻塞模式

            //new HttpMessageReader();用工厂解耦合
            newSocket.messageReader = this.messageReaderFactory.createMessageReader();
            newSocket.messageReader.init(this.readMessageBuffer);

            newSocket.messageWriter = new MessageWriter();

            this.socketMap.put(newSocket.socketId, newSocket);

            SelectionKey key = newSocket.socketChannel.register(this.readSelector, SelectionKey.OP_READ);//注册上读selector
            key.attach(newSocket);//将newSocket附着到SelectionKey上，SelectKey对象可以通过调用attachment()方法获取到这个对象

            newSocket = this.inboundSocketQueue.poll();
        }
    }


    public void readFromSockets() throws IOException {
        int readReady = this.readSelector.selectNow();//立刻返回当前有几个"读就绪"的socket,非阻塞

        if(readReady > 0){
            //每一次都会重新添加所有已就绪的key
            //返回当前已经"读就绪"的socket，selector只负责向set中添加元素，而不负责删除，需要手动删除
            Set<SelectionKey> selectedKeys = this.readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                readFromSocket(key);

                keyIterator.remove();
            }
            selectedKeys.clear();//清除所有的注册socket
        }
    }

    private void readFromSocket(SelectionKey key) throws IOException {
        Socket socket = (Socket) key.attachment();//获取到封装好的socket对象
        socket.messageReader.read(socket, this.readByteBuffer);//读取socket内的消息

        List<Message> fullMessages = socket.messageReader.getMessages();
        //根据协议，哪些数据是读取完成的，对这些已经接收完的消息，可以开始处理
        if(fullMessages.size() > 0){
            for(Message message : fullMessages){
                message.socketId = socket.socketId;
                //处理读到的消息
                this.messageProcessor.process(message, this.writeProxy);  //the message processor will eventually push outgoing messages into an IMessageWriter for this socket.
            }
            fullMessages.clear();
        }

        if(socket.endOfStreamReached){
            System.out.println("Socket closed: " + socket.socketId);
            this.socketMap.remove(socket.socketId);
            key.attach(null);
            key.cancel();
            key.channel().close();
        }
    }


    public void writeToSockets() throws IOException {

        // Take all new messages from outboundMessageQueue
        takeNewOutboundMessages();//从读完成队列中取出所有socket放入待写队列

        // Cancel all sockets which have no more data to write.
        cancelEmptySockets();//注销所有写完成队列里的socket

        // Register all sockets that *have* data and which are not yet registered.
        registerNonEmptySockets();

        // Select from the Selector.
        int writeReady = this.writeSelector.selectNow();

        if(writeReady > 0){
            Set<SelectionKey>      selectionKeys = this.writeSelector.selectedKeys();//选择所有写就绪的socket
            Iterator<SelectionKey> keyIterator   = selectionKeys.iterator();

            while(keyIterator.hasNext()){
                SelectionKey key = keyIterator.next();

                Socket socket = (Socket) key.attachment();//

                //每次只处理一条写的消息，写的过程中，如果消息已经处理完成，则会把消息移除
                socket.messageWriter.write(socket, this.writeByteBuffer);//每次最多只写writeByteBuffer指定长度的数据

                if(socket.messageWriter.isEmpty()){
                    //所有消息都处理完，当前的socket被加入已完成队列
                    this.nonEmptySockets.add(socket);
                }
                /*
                There are 2 tables why keyIterator.remove() should be executed:
                1、registration table: when we call channel.register, there will be a new item(key) into it.
                Only if we call key.cancel(), it will be removed from this table.
                2、ready for selection table: when we call selector.select(), the
                selector will look up the registration table, find the keys which
                are available, copy the references of them to this selection table.
                The items of this table won't be cleared by selector(that means,
                even if we call selector.select() again, it won't clear the existing items)
                 */
                keyIterator.remove();
            }

            selectionKeys.clear();

        }
    }

    private void registerNonEmptySockets() throws ClosedChannelException {
        for(Socket socket : emptySockets){
            socket.socketChannel.register(this.writeSelector, SelectionKey.OP_WRITE, socket);
        }
        emptySockets.clear();
    }

    private void cancelEmptySockets() {
        for(Socket socket : nonEmptySockets){
            SelectionKey key = socket.socketChannel.keyFor(this.writeSelector);

            key.cancel();
        }
        nonEmptySockets.clear();
    }

    private void takeNewOutboundMessages() {
        Message outMessage = this.outboundMessageQueue.poll();
        while(outMessage != null){
            Socket socket = this.socketMap.get(outMessage.socketId);

            if(socket != null){
                MessageWriter messageWriter = socket.messageWriter;//每个socket对应一个messageWriter
                if(messageWriter.isEmpty()){
                    //从读完成队列里取出的消息没有任何消息需要处理，证明是新来的，需要把消息保存起来,并加入emptySockets,
                    messageWriter.enqueue(outMessage);
                    nonEmptySockets.remove(socket);
                    emptySockets.add(socket);    //not necessary if removed from nonEmptySockets in prev. statement.
                } else{
                    //不是新来的，则将消息入socket的消息队列
                   messageWriter.enqueue(outMessage);
                }
            }

            outMessage = this.outboundMessageQueue.poll();
        }
    }

}
