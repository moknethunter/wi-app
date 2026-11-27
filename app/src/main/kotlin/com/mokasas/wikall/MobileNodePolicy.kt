package com.mokasas.wikall

/** Healthy DHCP listeners can await peers, but invalid/stopped/unknown engines cannot. */
internal fun mayRemainBootstrapNode(request: OverlayRequest, snapshot: EasyTierSnapshot?): Boolean =
    request.number != HOST_NUMBER && request.seedAddress == null &&
        snapshot?.running == true && snapshot.address == null
