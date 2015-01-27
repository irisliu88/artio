/**
 * Strawman API proposal where:
 *
 * Clients implement a specific interface for each message type.
 *
 * The message types and parameters to be handled would be generated from a dictionary
 *
 * Pros: a more semantic API
 * Cons: if all the handlers do is to serialise messages into a binary format its a harder API to use.
 *
 * Can use Chain of responsibility pattern for hooking our own code in.
 */
package uk.co.real_logic.fix_gateway.specific_callback_api;
