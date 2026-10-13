// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface IPresence {
    function isPresent(bytes32 identity, uint8 minAssurance, uint32 maxAgeDays) external view returns (bool);
}

/// @title HumanGatedVault
/// @notice Example consumer of PresenceRegistry for the AI-agent era: an agent key can move small amounts on its
/// own, but any withdrawal above `limit` needs proof that the owner (a real person) was physically present recently.
/// A compromised or runaway agent can't drain the vault by tapping, because presence can't be produced by taps.
contract HumanGatedVault {
    IPresence public immutable presence;
    address public immutable owner;
    address public agent;
    bytes32 public immutable ownerIdentity; // the owner's PRESENCE identity (hash of their device identity)
    uint256 public immutable limit; // max wei the agent may withdraw without a fresh presence proof
    uint8 public constant MIN_ASSURANCE = 2; // process evidence verified
    uint32 public constant MAX_AGE_DAYS = 1; // proof from today or yesterday (UTC)

    event Withdrawn(address indexed by, address indexed to, uint256 amount, bool humanVerified);

    error NotAuthorized();
    error HumanPresenceRequired();
    error TransferFailed();

    constructor(IPresence presence_, bytes32 ownerIdentity_, address agent_, uint256 limit_) {
        presence = presence_;
        owner = msg.sender;
        ownerIdentity = ownerIdentity_;
        agent = agent_;
        limit = limit_;
    }

    receive() external payable {}

    function humanPresent() public view returns (bool) {
        return presence.isPresent(ownerIdentity, MIN_ASSURANCE, MAX_AGE_DAYS);
    }

    function withdraw(address payable to, uint256 amount) external {
        if (msg.sender != agent && msg.sender != owner) revert NotAuthorized();
        bool verified = amount <= limit ? false : humanPresent();
        if (amount > limit && !verified) revert HumanPresenceRequired();
        (bool ok,) = to.call{value: amount}("");
        if (!ok) revert TransferFailed();
        emit Withdrawn(msg.sender, to, amount, verified);
    }
}
