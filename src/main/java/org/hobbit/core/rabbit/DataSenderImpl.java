package org.hobbit.core.rabbit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

import org.apache.commons.io.IOUtils;
import org.hobbit.core.Constants;
import org.hobbit.core.data.RabbitQueue;
import org.hobbit.core.utils.IdGenerator;
import org.hobbit.core.utils.RandomIdGenerator;
import org.hobbit.core.utils.SteppingIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ConfirmListener;

/**
 * Implementation of the {@link DataSender} interface.
 * 
 * <p>
 * Use the internal {@link Builder} class for creating instances of the
 * {@link DataSenderImpl} class. <b>Note</b> that the created
 * {@link DataSenderImpl} will either use a given {@link RabbitQueue} or create
 * a new one. In both cases the receiver will become the owner of the queue,
 * i.e., if the {@link DataSenderImpl} instance is closed the queue will be
 * closed as well.
 * </p>
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public class DataSenderImpl implements DataSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataSenderImpl.class);

    private static final int DEFAULT_MESSAGE_SIZE = 65536;
    private static final int DEFAULT_MESSAGE_BUFFER_SIZE = 100;
    private static final int DEFAULT_DELIVERY_MODE = 2;

    protected IdGenerator idGenerator = new SteppingIdGenerator();
    private RabbitQueue queue;
    private final int messageSize;
    private final int maxMessageSize;
    private final int deliveryMode;
    private final DataSenderConfirmHandler confirmHandler;

    protected DataSenderImpl(RabbitQueue queue, IdGenerator idGenerator, int messageSize, int deliveryMode,
            int messageConfirmBuffer) {
        this.queue = queue;
        this.idGenerator = idGenerator;
        this.messageSize = messageSize;
        this.maxMessageSize = 2 * messageSize;
        this.deliveryMode = deliveryMode;

        if (messageConfirmBuffer > 0) {
            try {
                this.queue.channel.confirmSelect();
            } catch (Exception e) {
                LOGGER.error(
                        "Exception whily trying to enable confirms. The sender might work, but it won't guarantee that messages are received.");
                confirmHandler = null;
                return;
            }
            confirmHandler = new DataSenderConfirmHandler(messageConfirmBuffer);
            this.queue.channel.addConfirmListener(confirmHandler);
        } else {
            confirmHandler = null;
        }
    }

    @Override
    public void sendData(byte[] data) throws IOException {
        sendData(data, idGenerator.getNextId());
    }

    @Override
    public void sendData(byte[] data, String dataId) throws IOException {
        sendData(new ByteArrayInputStream(data), dataId);
    }

    @Override
    public void sendData(InputStream is) throws IOException {
        sendData(is, idGenerator.getNextId());
    }

    @Override
    public void sendData(InputStream is, String dataId) throws IOException {
        sendData(is, dataId, new BasicProperties.Builder());
    }

    protected void sendData(InputStream is, String dataId, BasicProperties.Builder probBuilder) throws IOException {
        int messageId = 0;
        int length = 0;
        int dataPos = 0;
        byte[] buffer = new byte[maxMessageSize];
        probBuilder.correlationId(dataId);
        probBuilder.deliveryMode(deliveryMode);
        while (true) {
            length = is.read(buffer, dataPos, buffer.length - dataPos);
            // if the stream is at its end
            if (length < 0) {
                // send last message
                probBuilder.messageId(Integer.toString(messageId));
                probBuilder.type(Constants.END_OF_STREAM_MESSAGE_TYPE);
                if (confirmHandler != null) {
                    confirmHandler.sendDataWithConfirmation(probBuilder.build(), Arrays.copyOf(buffer, dataPos));
                } else {
                    sendData(probBuilder.build(), Arrays.copyOf(buffer, dataPos));
                }
                return;
            } else {
                dataPos += length;
                if (dataPos >= messageSize) {
                    probBuilder.messageId(Integer.toString(messageId));
                    if (confirmHandler != null) {
                        confirmHandler.sendDataWithConfirmation(probBuilder.build(), Arrays.copyOf(buffer, dataPos));
                    } else {
                        sendData(probBuilder.build(), Arrays.copyOf(buffer, dataPos));
                    }
                    ++messageId;
                    dataPos = 0;
                }
            }
        }
    }

    protected void sendData(BasicProperties properties, byte[] data) throws IOException {
        queue.channel.basicPublish("", queue.name, properties, data);
    }

    @Override
    public void closeWhenFinished() {
        // If we want to make sure that all messages are delivered we have to
        // wait until all messages are consumed
        if (confirmHandler != null) {
            try {
                confirmHandler.waitForConfirms();
            } catch (InterruptedException e) {
                LOGGER.warn(
                        "Exception while waiting for confirmations. It can not be guaranteed that all messages have been consumed.",
                        e);
            }
        } else {
            try {
                // Simply check whether the queue is empty. If the check is true
                // 5
                // times, we can assume that it is empty
                int check = 0;
                while (check < 5) {
                    if (queue.messageCount() > 0) {
                        check = 0;
                    } else {
                        ++check;
                    }
                    Thread.sleep(200);
                }
            } catch (AlreadyClosedException e) {
                LOGGER.info("The queue is already closed. Assuming that all messages have been consumed.");
            } catch (Exception e) {
                LOGGER.warn(
                        "Exception while trying to check whether all messages have been consumed. It will be ignored.",
                        e);
            }
        }
        close();
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(queue);
    }

    /**
     * Returns a newly created {@link Builder}.
     * 
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        protected static final String QUEUE_INFO_MISSING_ERROR = "There are neither a queue nor a queue name and a queue factory provided for the DataSender. Either a queue or a name and a factory to create a new queue are mandatory.";

        protected IdGenerator idGenerator = new RandomIdGenerator();
        protected RabbitQueue queue;
        protected String queueName;
        protected RabbitQueueFactory factory;
        protected int messageSize = DEFAULT_MESSAGE_SIZE;
        protected int messageConfirmBuffer = DEFAULT_MESSAGE_BUFFER_SIZE;
        protected int deliveryMode = DEFAULT_DELIVERY_MODE;

        public Builder() {
        };

        /**
         * Sets the Id generator used to create unique stream Ids.
         * 
         * @param dataHandler
         *            the Id generator used to create unique stream Ids
         * @return this builder instance
         */
        public Builder idGenerator(IdGenerator idGenerator) {
            this.idGenerator = idGenerator;
            return this;
        }

        /**
         * Sets the queue that is used to receive data.
         * 
         * @param queue
         *            the queue that is used to receive data
         * @return this builder instance
         */
        public Builder queue(RabbitQueue queue) {
            this.queue = queue;
            return this;
        }

        /**
         * Method for providing the necessary information to create a queue if
         * it has not been provided with the {@link #queue(RabbitQueue)} method.
         * Note that this information is not used if a queue has been provided.
         * 
         * @param factory
         *            the queue factory used to create a queue
         * @param queueName
         *            the name of the newly created queue
         * @return this builder instance
         */
        public Builder queue(RabbitQueueFactory factory, String queueName) {
            this.factory = factory;
            this.queueName = queueName;
            return this;
        }

        /**
         * Sets the size of the messages that will be send. Note that the
         * maximum size of a message can be 2 * the given value.
         * 
         * @param messageSize
         *            the size of the messages that will be send
         * @return this builder instance
         */
        public Builder messageSize(int messageSize) {
            this.messageSize = messageSize;
            return this;
        }

        /**
         * <p>
         * Sets the number of messages that are buffered while waiting for a
         * confirmation that they have been received by the broker. Note that if
         * the message buffer has reached is maximum size, the sender will block
         * until confirmations are received.
         * </p>
         * <p>
         * If the given message buffer size is {@code <1} the usage of
         * confirmation messages is turned off.
         * </p>
         * 
         * @param messageConfirmBuffer
         *            the size of the messages buffer
         * @return this builder instance
         */
        public Builder messageBuffer(int messageConfirmBuffer) {
            this.messageConfirmBuffer = messageConfirmBuffer;
            return this;
        }

        /**
         * Sets the delivery mode used for the RabbitMQ messages. Please have a
         * look into the RabbitMQ documentation to see the different meanings of
         * the values. By default, the sender uses
         * {@link DataSenderImpl#DEFAULT_DELIVERY_MODE}.
         * 
         * @param deliveryMode
         *            the delivery mode used for the RabbitMQ messages
         * @return this builder instance
         */
        public Builder deliveryMode(int deliveryMode) {
            this.deliveryMode = deliveryMode;
            return this;
        }

        /**
         * Builds the {@link DataReceiverImpl} instance with the previously
         * given information.
         * 
         * @return The newly created DataReceiver instance
         * @throws IllegalStateException
         *             if neither a queue nor the information needed to create a
         *             queue have been provided.
         * @throws IOException
         *             if an exception is thrown while creating a new queue.
         */
        public DataSenderImpl build() throws IllegalStateException, IOException {
            if (queue == null) {
                if ((queueName == null) || (factory == null)) {
                    throw new IllegalStateException(QUEUE_INFO_MISSING_ERROR);
                } else {
                    queue = factory.createDefaultRabbitQueue(queueName);
                }
            }
            return new DataSenderImpl(queue, idGenerator, messageSize, deliveryMode, messageConfirmBuffer);
        }
    }

    protected static class Message {
        public BasicProperties properties;
        public byte[] data;

        public Message(BasicProperties properties, byte[] data) {
            this.properties = properties;
            this.data = data;
        }
    }

    protected class DataSenderConfirmHandler implements ConfirmListener {

        private final Semaphore maxBufferedMessageCount;
        private final SortedMap<Long, Message> unconfirmedMsgs = Collections
                .synchronizedSortedMap(new TreeMap<Long, Message>());

        public DataSenderConfirmHandler(int messageConfirmBuffer) {
            this.maxBufferedMessageCount = new Semaphore(messageConfirmBuffer);
        }

        public synchronized void sendDataWithConfirmation(BasicProperties properties, byte[] data) throws IOException {
            synchronized (unconfirmedMsgs) {
                try {
                    System.out.println(DataSenderImpl.this.toString() + "\tavailable\t" + maxBufferedMessageCount.availablePermits());
                    maxBufferedMessageCount.acquire();
                } catch (InterruptedException e) {
                    throw new IOException(
                            "Interrupted while waiting for free buffer to store the message before sending.", e);
                }
                sendData_unsecured(new Message(properties, data));
            }
        }

        private void sendData_unsecured(Message message) throws IOException {
            // Get ownership of the channel to make sure that nobody else is
            // using it while we get the next sequence number and send the next
            // data
            synchronized (queue.channel) {
                long sequenceNumber = queue.channel.getNextPublishSeqNo();
                System.out.println(DataSenderImpl.this.toString() + "\tsending\t" + sequenceNumber);
                unconfirmedMsgs.put(sequenceNumber, message);
                try {
                    sendData(message.properties, message.data);
                } catch (IOException e) {
                    // the message hasn't been sent, remove it from the set
                    unconfirmedMsgs.remove(sequenceNumber);
                    maxBufferedMessageCount.release();
                    throw e;
                }
            }
        }

        @Override
        public void handleAck(long deliveryTag, boolean multiple) throws IOException {
            synchronized (unconfirmedMsgs) {
                if (multiple) {
                    // Remove all acknowledged messages
                    SortedMap<Long, Message> negativeMsgs = unconfirmedMsgs.headMap(deliveryTag + 1);
                    int ackMsgCount = negativeMsgs.size();
                    negativeMsgs.clear();
                    maxBufferedMessageCount.release(ackMsgCount);
                    System.out.println(DataSenderImpl.this.toString() + "\tack\t" + deliveryTag + "+\t" + maxBufferedMessageCount.availablePermits());
                } else {
                    // Remove the message
                    unconfirmedMsgs.remove(deliveryTag);
                    maxBufferedMessageCount.release();
                    System.out.println(DataSenderImpl.this.toString() + "\tack\t" + deliveryTag + "\t" + maxBufferedMessageCount.availablePermits());
                }
            }
        }

        @Override
        public void handleNack(long deliveryTag, boolean multiple) throws IOException {
            synchronized (unconfirmedMsgs) {
                System.out.println("nack " + deliveryTag + (multiple ? "+" : ""));
                if (multiple) {
                    // Resend all lost messages
                    SortedMap<Long, Message> negativeMsgs = unconfirmedMsgs.headMap(deliveryTag + 1);
                    Message messageToResend[] = negativeMsgs.values().toArray(new Message[negativeMsgs.size()]);
                    negativeMsgs.clear();
                    for (int i = 0; i < messageToResend.length; ++i) {
                        sendData_unsecured(messageToResend[i]);
                    }
                } else {
                    if (unconfirmedMsgs.containsKey(deliveryTag)) {
                        // send the lost message again
                        Message message = unconfirmedMsgs.remove(deliveryTag);
                        sendData_unsecured(message);
                    } else {
                        LOGGER.warn(
                                "Got a negative acknowledgement (nack) for an unknown message. It will be ignored.");
                    }
                }
            }
        }

        public void waitForConfirms() throws InterruptedException {
            while (true) {
                synchronized (unconfirmedMsgs) {
                    if (unconfirmedMsgs.size() == 0) {
                        return;
                    }
                }
                Thread.sleep(200);
            }
        }

    }

}