package ca.szc.groovy.pnc

import groovy.json.JsonOutput
import groovy.transform.InheritConstructors

@InheritConstructors
class ExecutionException extends Exception {}

@InheritConstructors
class ModelCoerceException extends Exception {}

@InheritConstructors
class ServerException extends Exception {}

@InheritConstructors
class AuthException extends Exception {}
