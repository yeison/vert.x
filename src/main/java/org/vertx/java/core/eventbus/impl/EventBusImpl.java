/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.core.eventbus.impl;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.impl.Context;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.net.NetClient;
import org.vertx.java.core.net.NetServer;
import org.vertx.java.core.net.NetSocket;
import org.vertx.java.core.net.impl.ServerID;
import org.vertx.java.core.parsetools.RecordParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class EventBusImpl extends EventBus {

  private static final Logger log = LoggerFactory.getLogger(EventBusImpl.class);

  private final ServerID serverID;
  private final NetServer server;
  private final AsyncMultiMap<String, ServerID> subs;  // Multimap name -> Collection<node ids>
  private final ConcurrentMap<ServerID, ConnectionHolder> connections = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Map<HandlerHolder, String>> handlers = new ConcurrentHashMap<>();
  private final Map<String, ServerID> replyAddressCache = new ConcurrentHashMap<>();
  private final Map<String, HandlerInfo> handlersByID = new ConcurrentHashMap<>();

  /*
  Non clustered event bus
   */
  protected EventBusImpl() {
    // Just some dummy server ID
    this.serverID = new ServerID(2550, "localhost");
    this.server = null;
    this.subs = null;
  }

  /*
  Clustered event bus
   */
  protected EventBusImpl(ServerID serverID, ClusterManager clusterManager) {
    this.serverID = serverID;
    subs = clusterManager.getMultiMap("subs");
    this.server = setServer();
  }

  public void send(String address, JsonObject message, final Handler<Message<JsonObject>> replyHandler) {
    send(new JsonMessage(address, message), replyHandler);
  }

  public void send(String address, JsonObject message) {
    send(address, message, null);
  }

  public void send(String address, Buffer message, final Handler<Message<Buffer>> replyHandler) {
    send(new BufferMessage(address, message), replyHandler);
  }

  public void send(String address, Buffer message) {
    send(address, message, null);
  }

  public void send(String address, byte[] message, final Handler<Message<byte[]>> replyHandler) {
    send(new ByteArrayMessage(address, message), replyHandler);
  }

  public void send(String address, byte[] message) {
    send(address, message, null);
  }

  public void send(String address, String message, final Handler<Message<String>> replyHandler) {
    send(new StringMessage(address, message), replyHandler);
  }

  public void send(String address, String message) {
    send(address, message, null);
  }

  public void send(String address, Integer message, final Handler<Message<Integer>> replyHandler) {
    send(new IntMessage(address, message), replyHandler);
  }

  public void send(String address, Integer message) {
    send(address, message, null);
  }

  public void send(String address, Long message, final Handler<Message<Long>> replyHandler) {
    send(new LongMessage(address, message), replyHandler);
  }

  public void send(String address, Long message) {
    send(address, message, null);
  }

  public void send(String address, Float message, final Handler<Message<Float>> replyHandler) {
    send(new FloatMessage(address, message), replyHandler);
  }

  public void send(String address, Float message) {
    send(address, message, null);
  }

  public void send(String address, Double message, final Handler<Message<Double>> replyHandler) {
    send(new DoubleMessage(address, message), replyHandler);
  }

  public void send(String address, Double message) {
    send(address, message, null);
  }

  public void send(String address, Boolean message, final Handler<Message<Boolean>> replyHandler) {
    send(new BooleanMessage(address, message), replyHandler);
  }

  public void send(String address, Boolean message) {
    send(address, message, null);
  }

  public void send(String address, Short message, final Handler<Message<Short>> replyHandler) {
    send(new ShortMessage(address, message), replyHandler);
  }

  public void send(String address, Short message) {
    send(address, message, null);
  }

  public void send(String address, Character message, final Handler<Message<Character>> replyHandler) {
    send(new CharacterMessage(address, message), replyHandler);
  }

  public void send(String address, Character message) {
    send(address, message, null);
  }

  public void send(String address, Byte message, final Handler<Message<Byte>> replyHandler) {
    send(new ByteMessage(address, message), replyHandler);
  }

  public void send(String address, Byte message) {
    send(address, message, null);
  }

  public void unregisterHandler(String address, Handler<? extends Message> handler,
                                AsyncResultHandler<Void> completionHandler) {
    Map<HandlerHolder, String> map = handlers.get(address);
    if (map != null) {
      String handlerID = map.remove(new HandlerHolder(handler, false));
      if (handlerID != null) {
        handlersByID.remove(handlerID);
      }
      if (map.isEmpty()) {
        handlers.remove(address);
        if (subs != null) {
          removeSub(address, serverID, completionHandler);
        } else if (completionHandler != null) {
          callCompletionHandler(completionHandler);
        }
      } else if (completionHandler != null) {
        callCompletionHandler(completionHandler);
      }
    }
  }

  public void unregisterHandler(String address, Handler<? extends Message> handler) {
    unregisterHandler(address, handler, null);
  }

  public void unregisterHandler(String id) {
    unregisterHandler(id, (AsyncResultHandler<Void>) null);
  }

  public void unregisterHandler(String id, AsyncResultHandler<Void> completionHandler) {
    HandlerInfo info = handlersByID.get(id);
    if (info != null) {
      unregisterHandler(info.address, info.handler, completionHandler);
    }
  }

  public String registerHandler(Handler<? extends Message> handler) {
    return registerHandler(handler, null);
  }

  public String registerHandler(Handler<? extends Message> handler,
                               AsyncResultHandler<Void> completionHandler) {
    return registerHandler(null, handler, completionHandler, false);
  }

  public String registerHandler(String address, Handler<? extends Message> handler,
                               AsyncResultHandler<Void> completionHandler) {
    return registerHandler(address, handler, completionHandler, false);
  }

  public String registerHandler(String address, Handler<? extends Message> handler) {
    return registerHandler(address, handler, null);
  }

  protected void close(Handler<Void> doneHandler) {
    server.close(doneHandler);
  }

  private NetServer setServer() {
    return new NetServer().connectHandler(new Handler<NetSocket>() {
      public void handle(NetSocket socket) {
        final RecordParser parser = RecordParser.newFixed(4, null);
        Handler<Buffer> handler = new Handler<Buffer>() {
          int size = -1;
          public void handle(Buffer buff) {
            if (size == -1) {
              size = buff.getInt(0);
              parser.fixedSizeMode(size);
            } else {
              BaseMessage received = MessageFactory.read(buff);
              receiveMessage(received);
              parser.fixedSizeMode(4);
              size = -1;
            }
          }
        };
        parser.setOutput(handler);
        socket.dataHandler(parser);
      }
    }).listen(serverID.port, serverID.host);
  }


  private void send(final BaseMessage message, final Handler replyHandler) {

    Context context = VertxInternal.instance.getContext();
    try {
      message.sender = serverID;
      if (replyHandler != null) {
        message.replyAddress = UUID.randomUUID().toString();
        registerHandler(message.replyAddress, replyHandler, null, true);
      }

      // First check if sender is in response address cache - it will be if it's a reply
      ServerID theServerID = replyAddressCache.remove(message.address);

      if (theServerID != null) {
        // Yes, it's a response to a particular server
        if (!theServerID.equals(this.serverID)) {
          sendRemote(theServerID, message);
        } else {
          receiveMessage(message);
        }
      } else {
        if (subs != null) {
          subs.get(message.address, new AsyncResultHandler<Collection<ServerID>>() {
            public void handle(AsyncResult<Collection<ServerID>> event) {              
              if (event.exception == null) {
                Collection<ServerID> serverIDs = event.result;
                if (serverIDs != null) {
                  for (ServerID serverID : serverIDs) {
                    if (!serverID.equals(EventBusImpl.this.serverID)) {  //We don't send to this node
                      sendRemote(serverID, message);
                    }
                  }
                }
              } else {
                log.error("Failed to send message", event.exception);
              }
            }
          });
        }
        //also send locally
        receiveMessage(message);
      }
    } finally {
      // Reset the context id - send can cause messages to be delivered in different contexts so the context id
      // of the current thread can change
      if (context != null) {
        VertxInternal.instance.setContext(context);
      }
    }
  }

  private String registerHandler(String address, Handler<? extends Message> handler,
                               AsyncResultHandler<Void> completionHandler,
                               boolean replyHandler) {
    String id = UUID.randomUUID().toString();
    if (address == null) {
      address = id;
    }
    handlersByID.put(id, new HandlerInfo(address, handler));
    Map<HandlerHolder, String> map = handlers.get(address);
    if (map == null) {
      map = new ConcurrentHashMap<>();
      Map<HandlerHolder, String> prevMap = handlers.putIfAbsent(address, map);
      if (prevMap != null) {
        map = prevMap;
      }
      if (completionHandler == null) {
        completionHandler = new AsyncResultHandler<Void>() {
          public void handle(AsyncResult<Void> event) {
            if (event.exception != null) {
              log.error("Failed to remove entry", event.exception);
            }
          }
        };
      }
      map.put(new HandlerHolder(handler, replyHandler), id);
      if (subs != null && !replyHandler) {
        // Propagate the information
        subs.put(address, serverID, completionHandler);
      } else {
        if (completionHandler != null) {
          callCompletionHandler(completionHandler);
        }
      }
    } else {
      map.put(new HandlerHolder(handler, replyHandler), id);
      if (completionHandler != null) {
        callCompletionHandler(completionHandler);
      }
    }
    return id;
  }

  private void callCompletionHandler(AsyncResultHandler<Void> completionHandler) {
    AsyncResult<Void> f = new AsyncResult<>((Void)null);
    completionHandler.handle(f);
  }

  private void sendRemote(final ServerID serverID, final BaseMessage message) {
    //We need to deal with the fact that connecting can take some time and is async, and we cannot
    //block to wait for it. So we add any sends to a pending list if not connected yet.
    //Once we connect we send them.
    ConnectionHolder holder = connections.get(serverID);
    if (holder == null) {
      NetClient client = new NetClient();
      holder = new ConnectionHolder(client);
      ConnectionHolder prevHolder = connections.putIfAbsent(serverID, holder);
      if (prevHolder != null) {
        holder = prevHolder;
      }
      holder.pending.add(message);
      final ConnectionHolder fholder = holder;
      client.connect(serverID.port, serverID.host, new Handler<NetSocket>() {
        public void handle(NetSocket socket) {
          fholder.socket = socket;
          for (BaseMessage message : fholder.pending) {
            message.write(socket);
          }
          fholder.connected = true;
        }
      });
      client.exceptionHandler(new Handler<Exception>() {
        public void handle(Exception e) {
          log.debug("Cluster connection failed. Removing it from map");
          connections.remove(serverID);
          if (subs != null) {
            removeSub(message.address, serverID, null);
          }
        }
      });
    } else {
      if (holder.connected) {
        message.write(holder.socket);
      } else {
        holder.pending.add(message);
      }
    }
  }

  private void removeSub(String subName, ServerID serverID, final AsyncResultHandler<Void> completionHandler) {
    subs.remove(subName, serverID, new AsyncResultHandler<Boolean>() {
      public void handle(AsyncResult<Boolean> event) {
        if (completionHandler != null) {
          AsyncResult<Void> result;
          if (event.exception != null) {
            result = new AsyncResult<Void>(event.exception);
          } else {
            result = new AsyncResult<Void>((Void)null);
          }
          completionHandler.handle(result);
        } else {
          if (event.exception != null) {
            log.error("Failed to remove subscription", event.exception);
          }
        }
      }
    });
  }

  // Called when a message is incoming
  private void receiveMessage(BaseMessage msg) {
    if (msg.replyAddress != null) {
      replyAddressCache.put(msg.replyAddress, msg.sender);
    }
    msg.bus = this;
    final Map<HandlerHolder, String> map = handlers.get(msg.address);
    if (map != null) {
      boolean replyHandler = false;
      for (final HandlerHolder holder: map.keySet()) {
        if (holder.replyHandler) {
          replyHandler = true;
        }
        // Each handler gets a fresh copy
        final Message copied = msg.copy();

        holder.context.execute(new Runnable() {
          public void run() {
            // Need to check handler is still there - the handler might have been removed after the message were sent but
            // before it was received
            if (map.containsKey(holder)) {
              holder.handler.handle(copied);
            }
          }
        });
      }
      if (replyHandler) {
        handlers.remove(msg.address);
      }
    }
  }

  private static class HandlerHolder {
    final Context context;
    final Handler handler;
    final boolean replyHandler;

    HandlerHolder(Handler handler, boolean replyHandler) {
      this.context = VertxInternal.instance.getContext();
      this.handler = handler;
      this.replyHandler = replyHandler;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      HandlerHolder that = (HandlerHolder) o;
      return handler.equals(that.handler);
    }

    @Override
    public int hashCode() {
      return handler.hashCode();
    }
  }

  private static class ConnectionHolder {
    final NetClient client;
    NetSocket socket;
    final List<BaseMessage> pending = new ArrayList<>();
    boolean connected;

    private ConnectionHolder(NetClient client) {
      this.client = client;
    }
  }

  private static class HandlerInfo {
    final String address;
    final Handler<? extends Message> handler;

    private HandlerInfo(String address, Handler<? extends Message> handler) {
      this.address = address;
      this.handler = handler;
    }
  }

}

