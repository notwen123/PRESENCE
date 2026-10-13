// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title PresenceVault
/// @notice Holds FACEDOWN stakes and sponsor rewards in MON on Monad. Users stake into it and sponsors fund it;
/// only the ATTEST attestor pays out: stake back + sponsor bonus on a verified session, a refund for No-Loss,
/// the stake back after a Cool-down Lock, a sponsor reward for free sessions, and burns for streak freezes.
/// Nobody's stake is ever paid to another user.
contract PresenceVault {
    address public immutable admin;
    address public attestor;
    address public constant BURN = 0x000000000000000000000000000000000000dEaD;

    event Staked(bytes32 indexed ref, address indexed from, uint256 amount);
    event Funded(address indexed from, uint256 amount);
    event Paid(address indexed to, uint256 amount, bytes32 indexed ref);
    event Burned(uint256 amount);

    error NotAttestor();
    error NotAdmin();
    error NothingSent();
    error TransferFailed();

    constructor(address attestor_) { admin = msg.sender; attestor = attestor_; }

    modifier onlyAttestor() { if (msg.sender != attestor) revert NotAttestor(); _; }

    function setAttestor(address a) external { if (msg.sender != admin) revert NotAdmin(); attestor = a; }

    /// @notice Lock MON for one session (or pay for one streak freeze). `ref` is the id ATTEST issued.
    function stake(bytes32 ref) external payable {
        if (msg.value == 0) revert NothingSent();
        emit Staked(ref, msg.sender, msg.value);
    }

    /// @notice Sponsors fund rewards.
    function fund() external payable {
        if (msg.value == 0) revert NothingSent();
        emit Funded(msg.sender, msg.value);
    }

    function pay(address payable to, uint256 amount, bytes32 ref) external onlyAttestor {
        (bool ok,) = to.call{value: amount}("");
        if (!ok) revert TransferFailed();
        emit Paid(to, amount, ref);
    }

    function burn(uint256 amount) external onlyAttestor {
        (bool ok,) = payable(BURN).call{value: amount}("");
        if (!ok) revert TransferFailed();
        emit Burned(amount);
    }
}
