// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface IPresenceDays {
    function presentDays(bytes32 identity, int64 fromDay, int64 toDay) external view returns (uint256);
    function today() external view returns (int64);
}

/// @title SustainedHumanAirdrop
/// @notice Example: pays once per identity, only to humans verified present on at least `minDays` of the last
/// `window` days. A farm has to keep every fake identity physically present every day, so its cost grows linearly.
contract SustainedHumanAirdrop {
    IPresenceDays public immutable presence;
    uint256 public immutable amount;
    uint32 public immutable minDays;
    uint32 public immutable window;
    address public immutable attestor; // binds identity -> payout address (ATTEST knows which wallet owns which device)
    mapping(bytes32 => bool) public claimed;

    event Claimed(bytes32 indexed identity, address to, uint256 amount, uint256 daysPresent);
    error NotEnoughPresence(uint256 have, uint256 need);
    error AlreadyClaimed();
    error NotAttestor();

    constructor(IPresenceDays presence_, uint256 amount_, uint32 minDays_, uint32 window_, address attestor_) {
        presence = presence_; amount = amount_; minDays = minDays_; window = window_; attestor = attestor_;
    }

    receive() external payable {}

    function eligible(bytes32 identity) public view returns (uint256 daysPresent, bool ok) {
        int64 t = presence.today();
        daysPresent = presence.presentDays(identity, t - int64(uint64(window)) + 1, t);
        ok = daysPresent >= minDays && !claimed[identity];
    }

    function claim(bytes32 identity, address payable to) external {
        if (msg.sender != attestor) revert NotAttestor();
        if (claimed[identity]) revert AlreadyClaimed();
        (uint256 have,) = eligible(identity);
        if (have < minDays) revert NotEnoughPresence(have, minDays);
        claimed[identity] = true;
        (bool ok,) = to.call{value: amount}("");
        require(ok, "transfer failed");
        emit Claimed(identity, to, amount, have);
    }
}
