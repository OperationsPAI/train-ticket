/**
 * The namespaces a reference a caller sends arrives in.
 *
 * A service that keys by a reference another contract publishes holds a value it
 * does not mint: the account contract publishes an account reference, the
 * traveler contract a traveler reference, and each of them states the namespace
 * its references arrive in. A reference from a namespace the contract does not
 * state is a value that will not resolve, and the service that keys by it is the
 * one that finds out -- so the kit answers the question once, and every service
 * that asks it asks it the same way.
 */
export function referenceArrivesIn(reference, namespaces) {
    if (namespaces.length === 0) {
        return true;
    }
    return namespaces.some((namespace) => reference.startsWith(`${namespace}-`) || reference.startsWith(`${namespace}_`));
}
