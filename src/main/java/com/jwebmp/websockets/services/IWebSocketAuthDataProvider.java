package com.jwebmp.websockets.services;

import com.jwebmp.guicedinjection.interfaces.IDefaultService;

/**
 * Service to load authentication data for web service
 */
public interface IWebSocketAuthDataProvider<J extends IWebSocketAuthDataProvider<J>>
		extends IDefaultService<J>
{
	StringBuilder getJavascriptToPopulate();

	String name();
}
