// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title AgentGuard: "Knock to Approve" for AI agents
/// @notice Each account is one phone's P-256 key (account id = keccak256(x, y), bound once, never rebindable).
/// An AI agent can spend up to `limit` for an account on its own. A larger transfer becomes a pending intent that
/// executes only when that phone signs the exact intent after a physical knock. This contract checks the
/// signature itself with Monad's P-256 precompile (RIP-7212 at 0x100). ATTEST relays and co-signs (it checks the
/// knock evidence) but cannot approve a transfer the phone didn't sign, and a signature for one intent can't be
/// reused for another: it covers the intent id (guard, chain, account, to, amount, nonce).
contract AgentGuard {
    address public constant P256 = address(0x100);
    uint256 public constant WINDOW = 120; // seconds the human has to knock

    address public immutable owner;
    address public agent;
    address public attestor;
    uint256 public limit;
    uint256 public nonce;

    struct Key { bytes32 x; bytes32 y; }
    struct Intent { bytes32 account; address payable to; uint96 amount; uint64 deadline; bool done; }
    mapping(bytes32 => Key) public keys;
    mapping(bytes32 => Intent) public intents;

    event DeviceBound(bytes32 indexed account, bytes32 x, bytes32 y);
    event IntentRequested(bytes32 indexed intentId, bytes32 indexed account, address indexed to, uint256 amount, uint64 deadline);
    event Sent(bytes32 indexed account, address indexed to, uint256 amount, bytes32 intentId, bool humanApproved);

    error NotAgent(); error NotAttestor(); error NotOwner(); error NoDevice(); error AlreadyBound();
    error UnknownIntent(); error Expired(); error BadSignature(); error TransferFailed();

    constructor(address agent_, address attestor_, uint256 limit_) {
        owner = msg.sender; agent = agent_; attestor = attestor_; limit = limit_;
    }

    receive() external payable {}

    function accountOf(bytes32 x, bytes32 y) public pure returns (bytes32) { return keccak256(abi.encode(x, y)); }

    /// @notice Register a phone key. Anyone may relay it; the account id is derived from the key itself.
    function bindDevice(bytes32 x, bytes32 y) external returns (bytes32 account) {
        account = accountOf(x, y);
        if (keys[account].x != 0) revert AlreadyBound();
        keys[account] = Key(x, y);
        emit DeviceBound(account, x, y);
    }

    /// @notice The agent pays on an account's behalf. Small amounts go straight through; large ones wait for a knock.
    function send(bytes32 account, address payable to, uint256 amount) external returns (bytes32 intentId) {
        if (msg.sender != agent) revert NotAgent();
        if (amount <= limit) { _pay(account, to, amount, bytes32(0), false); return bytes32(0); }
        if (keys[account].x == 0) revert NoDevice();
        intentId = keccak256(abi.encode(address(this), block.chainid, account, to, amount, nonce++));
        uint64 deadline = uint64(block.timestamp + WINDOW);
        intents[intentId] = Intent(account, to, uint96(amount), deadline, false);
        emit IntentRequested(intentId, account, to, amount, deadline);
    }

    /// @notice Execute an intent its phone signed: ECDSA-P256 over sha256(intentId), verified on-chain.
    function approve(bytes32 intentId, bytes32 r, bytes32 s) external {
        if (msg.sender != attestor) revert NotAttestor();
        Intent storage it = intents[intentId];
        if (it.to == address(0) || it.done) revert UnknownIntent();
        if (block.timestamp > it.deadline) revert Expired();
        if (!verify(it.account, sha256(abi.encodePacked(intentId)), r, s)) revert BadSignature();
        it.done = true;
        _pay(it.account, it.to, it.amount, intentId, true);
    }

    function verify(bytes32 account, bytes32 digest, bytes32 r, bytes32 s) public view returns (bool) {
        Key memory k = keys[account];
        (bool ok, bytes memory out) = P256.staticcall(abi.encodePacked(digest, r, s, k.x, k.y));
        return ok && out.length == 32 && abi.decode(out, (uint256)) == 1;
    }

    function withdraw(address payable to, uint256 amount) external {
        if (msg.sender != owner) revert NotOwner();
        (bool ok,) = to.call{value: amount}("");
        if (!ok) revert TransferFailed();
    }

    function _pay(bytes32 account, address payable to, uint256 amount, bytes32 intentId, bool human) internal {
        (bool ok,) = to.call{value: amount}("");
        if (!ok) revert TransferFailed();
        emit Sent(account, to, amount, intentId, human);
    }
}
