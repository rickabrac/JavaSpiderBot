## Synopsis

Multi-threaded single domain web crawler in Java by Rick Tyler.

## Description

This crawler uses a breadth-first strategy to search an entire website, subdomains included. 
It honors the Crawl-delay setting in /robots.txt and prevents loading of disallowed pages. 

## System Requirements 

JDK 11, Maven 3.6.3, Unix shell (/bin/sh)

## Installation

• Open terminal window and navigate to the project directory.

• Enter 'mvn compile assembly:single'

• Enter './crawl <url>' and watch it go. 

## Sample Output

	./crawl https://duckduckgo.com

	OK https://duckduckgo.com/robots.txt
	• https://duckduckgo.com/about
		href=[https://duckduckgo.com/traffic]
		href=[https://duckduckgo.com/app]
		href=[https://duckduckgo.com/hiring]
		href=[/hiring]
		href=[/newsletter]
		href=[https://duckduckgo.com/assets/email/DuckDuckGo-Privacy-Weekly_sample.png]
		href=[https://spreadprivacy.com/delete-google-search-history]
		href=[https://duckduckgo.com/privacy]
		href=[https://duckduckgo.com/press]
		href=[https://spreadprivacy.com]
		href=[https://twitter.com/duckduckgo]
		href=[https://reddit.com/r/duckduckgo]
	ERROR 404 [https://duckduckgo.com/newsletter]
	/robots.txt DISALLOW [https://duckduckgo.com/search?foo=bar]

## Legend

	• Lines beginning with "•" indicate successfully crawled pages.

	• Indented lines with "href=[..."" indicate unique, crawlable URLs on the preceeding page.

	• The ERROR line indicates an HTTP file not found failure.

	• The last line indicates a page disallowed by /robots.txt

## Limitations

• Switching Protocols (See HTTP/1.1 101) is not supported.

• Pages that cannot be loaded due to transient failures should be retried.

• Robot meta tags not supported (https://developers.google.com/search/docs/advanced/robots/robots_meta_tag))

## Dependencies

• Uses crawlercommons.robots.* to enforce /robots.txt directives 
