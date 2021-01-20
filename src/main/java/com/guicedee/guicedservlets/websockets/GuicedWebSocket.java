package com.guicedee.guicedservlets.websockets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guicedee.guicedinjection.GuiceContext;
import com.guicedee.guicedservlets.websockets.options.WebSocketMessageReceiver;
import com.guicedee.guicedservlets.websockets.services.IWebSocketSessionProvider;
import com.guicedee.logger.LogFactory;
import com.guicedee.guicedservlets.websockets.services.IWebSocketMessageReceiver;
import com.guicedee.guicedservlets.websockets.services.IWebSocketService;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("unused")
@ServerEndpoint("/")
public class GuicedWebSocket
{
	@SuppressWarnings("WeakerAccess")
	public static final String EveryoneGroup = "Everyone";
	
	private static final Logger log = LogFactory.getLog("GuicedEEWebSocket");
	
	private static final Map<String, Set<Session>> groupedSessions = new ConcurrentHashMap<>();
	private static final Map<String, Session> webSocketSessionBindings = new ConcurrentHashMap<>();
	private static final Map<String, Set<Class<? extends IWebSocketMessageReceiver>>> messageListeners = new HashMap<>();
	
	private static final Map<Session, Map<String, String>> webSocketProperties = new ConcurrentHashMap<>();
	
	static
	{
		Set<IWebSocketMessageReceiver> messageReceivers = GuiceContext.instance()
		                                                              .getLoader(IWebSocketMessageReceiver.class, ServiceLoader.load(IWebSocketMessageReceiver.class));
		for (IWebSocketMessageReceiver messageReceiver : messageReceivers)
		{
			Set<String> actions = messageReceiver.messageNames();
			for (String action : actions)
			{
				if (!messageListeners.containsKey(action))
				{
					messageListeners.put(action, new HashSet<>());
				}
				messageListeners.get(action)
				                .add(messageReceiver.getClass());
			}
		}
	}
	
	public GuicedWebSocket()
	{
	}
	
	public static void removeFromGroup(String groupName, Session session)
	{
		getGroup(groupName)
				.remove(session);
	}
	
	public static Set<Session> getGroup(String groupName)
	{
		groupedSessions.computeIfAbsent(groupName, k -> new CopyOnWriteArraySet<>());
		return groupedSessions.get(groupName);
	}
	
	public static void remove(String id)
	{
		groupedSessions.forEach((key, value) ->
				value.removeIf(a -> a.getId()
				                     .equals(id)));
		
		for (Iterator<Map.Entry<String, Session>> iterator = webSocketSessionBindings.entrySet()
		                                                                             .iterator(); iterator.hasNext(); )
		{
			Map.Entry<String, Session> entry = iterator.next();
			String key = entry.getKey();
			if (key.equals(id))
			{
				iterator.remove();
			}
		}
	}
	
	/**
	 * Returns a session if valid that is linked to this session
	 *
	 * @param id
	 *
	 * @return
	 */
	public static HttpSession getLinkedSession(String id)
	{
		for (IWebSocketSessionProvider sessionProvider : GuiceContext.instance()
		                                                             .getLoader(IWebSocketSessionProvider.class, ServiceLoader.load(IWebSocketSessionProvider.class)))
		{
			HttpSession session = sessionProvider.getSession(id);
			if (session != null)
			{
				return session;
			}
		}
		return null;
	}
	
	@OnOpen
	public void onOpen(Session session)
	{
		addToGroup(EveryoneGroup, session);
		GuiceContext.instance()
		            .getLoader(IWebSocketService.class, ServiceLoader.load(IWebSocketService.class))
		            .forEach(a -> a.onOpen(session, this));
		log.fine("Opened web socket session -" + session.getId());
	}
	
	public static void addToGroup(String groupName, Session session)
	{
		getGroup(groupName)
				.add(session);
	}
	
	@OnClose
	public void onClose(Session session)
	{
		remove(session);
		GuiceContext.instance()
		            .getLoader(IWebSocketService.class, ServiceLoader.load(IWebSocketService.class))
		            .forEach(a -> a.onClose(session, this));
		log.fine("Removed web socket session -" + session.getId());
	}
	
	public static void remove(Session session)
	{
		for (Map.Entry<String, Set<Session>> entry : groupedSessions.entrySet())
		{
			List<Session> value = new ArrayList<>(entry.getValue());
			for (int i = 0; i < value.size(); i++)
			{
				if (value.get(i)
				         .getId()
				         .equals(session.getId()))
				{
					value.remove(i);
					break;
				}
			}
			if (value.isEmpty())
			{
				groupedSessions.remove(entry.getKey());
			}
		}
		for (Iterator<Map.Entry<String, Session>> iterator = webSocketSessionBindings.entrySet()
		                                                                             .iterator(); iterator.hasNext(); )
		{
			Map.Entry<String, Session> entry = iterator.next();
			Session value = entry.getValue();
			if (value.equals(session))
			{
				iterator.remove();
				break;
			}
		}
		for (Iterator<Map.Entry<Session, Map<String, String>>> iterator = webSocketProperties.entrySet()
		                                                                                     .iterator(); iterator.hasNext(); )
		{
			Map.Entry<Session, Map<String, String>> sessionMapEntry = iterator.next();
			if (sessionMapEntry.getKey()
			                   .getId()
			                   .equals(session.getId()))
			{
				iterator.remove();
				break;
			}
		}
	}
	
	@OnMessage
	public void onMessage(String message, Session session)
	{
		try
		{
			WebSocketMessageReceiver<?> messageReceived = GuiceContext.get(ObjectMapper.class)
			                                                          .readValue(message, WebSocketMessageReceiver.class);
			messageReceived.setBroadcastGroup(session.getId());
			messageReceived.setSession(session);
			GuicedWebSocket.addToGroup(session.getId(), session);
			log.log(Level.FINER, "Web Socket Message Received - " + session.getId() + " Message=" + messageReceived.toString());
			GuiceContext.instance()
			            .getLoader(IWebSocketService.class, ServiceLoader.load(IWebSocketService.class))
			            .forEach(a -> a.onMessage(message, session, messageReceived, this));
			if (messageListeners.containsKey(messageReceived.getAction()))
			{
				for (Class<? extends IWebSocketMessageReceiver> iWebSocketMessageReceiver : messageListeners.get(messageReceived.getAction()))
				{
					IWebSocketMessageReceiver messageReceiver = GuiceContext.get(iWebSocketMessageReceiver);
					messageReceiver.receiveMessage(messageReceived);
				}
			}
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "ERROR Message Received - " + session.getId() + " Message=" + message, e);
		}
	}
	
	/**
	 * A map of HttpSession ID's to WebSocket Sessions
	 *
	 * @return
	 */
	public static Map<String, Session> getWebSocketSessionBindings()
	{
		return webSocketSessionBindings;
	}
	
	/**
	 * Broadcast a given message to the web socket
	 *
	 * @param groupName The broadcast group to send to
	 * @param message   The message to send
	 */
	public static void broadcastMessage(String groupName, String message)
	{
		getGroup(groupName).forEach(session -> {
			try
			{
				session.getBasicRemote()
				       .sendText(message);
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		});
	}
	
	/**
	 * Associates keys and values to a web socket session,
	 * mostly used by security authenticators to grant access to their information
	 *
	 * @param session
	 * @param key
	 * @param value
	 */
	public static void addWebsocketProperty(Session session, String key, String value)
	{
		getPropertyMap(session)
				.put(key, value);
	}
	
	/**
	 * removes a property
	 * @param session
	 * @param key
	 * @param value
	 */
	public static void removeWebsocketProperty(Session session, String key, String value)
	{
		getPropertyMap(session)
				.remove(key);
	}
	
	public static boolean hasProperty(Session session, String key)
	{
		return getPropertyMap(session).containsKey(key);
	}
	
	public static Map<String, String> getPropertyMap(Session session)
	{
		if (!webSocketProperties.containsKey(session))
		{
			if (!webSocketProperties.containsKey(session))
			{
				webSocketProperties.put(session, new ConcurrentHashMap<>());
			}
		}
		return webSocketProperties.get(session);
	}
	
	@OnError
	public void onError(Throwable t, Session session)
	{
		log.log(Level.SEVERE, "Error occurred in WebSocket", t);
		GuiceContext.instance()
		            .getLoader(IWebSocketService.class, ServiceLoader.load(IWebSocketService.class))
		            .forEach(a -> a.onError(t, this));
		remove(session);
		log.config("Removed web socket session -" + session);
	}
}
