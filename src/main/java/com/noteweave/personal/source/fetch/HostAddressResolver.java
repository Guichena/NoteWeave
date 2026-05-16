package com.noteweave.personal.source.fetch;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

@FunctionalInterface
public interface HostAddressResolver {

    List<InetAddress> resolve(String host) throws Exception;

    static HostAddressResolver system() {
        return host -> Arrays.asList(InetAddress.getAllByName(host));
    }
}
