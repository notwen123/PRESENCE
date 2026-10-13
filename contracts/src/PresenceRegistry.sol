// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title PresenceRegistry
/// @notice On-chain record that a real person physically completed a process on a real device.
/// The ATTEST engine verifies each session off-chain (server-timed, hash-chained evidence, physical
/// knock check-ins) and records the verdict here once. Any contract can then ask `isPresent`.
/// Mirrors the Solana `presence` program: one attestation per (identity, mission, day, claim index).
contract PresenceRegistry {
    struct Attestation {
        bytes32 evidenceRoot; // 32-byte root of the session's evidence hash chain
        uint8 assurance; // 1 = wallet + device, 2 = + process evidence (P3/P4 reserved)
        uint64 recordedAt;
        address attestor;
    }

    struct Profile {
        uint32 streak; // consecutive UTC days with an attestation
        uint32 bestStreak;
        int64 lastDay; // UTC day number of the latest attestation (-1 = never)
        uint64 attestations;
        uint64 reputation; // sum of assurance levels
        uint8 lastAssurance;
    }

    address public admin;
    mapping(address => bool) public isAttestor;
    mapping(bytes32 => Attestation) internal _attestations; // key: attestationKey(...)
    mapping(bytes32 => Profile) internal _profiles; // key: identity (hash of the device identity)

    event AttestorSet(address indexed attestor, bool allowed);
    event PresenceAttested(
        bytes32 indexed identity, bytes32 indexed mission, int64 day, uint8 seq, bytes32 evidenceRoot,
        uint8 assurance, uint32 streak, address attestor
    );

    error NotAdmin();
    error NotAttestor();
    error BadAssurance();
    error DayOutOfRange();
    error AlreadyAttested();

    constructor(address attestor) {
        admin = msg.sender;
        isAttestor[attestor] = true;
        emit AttestorSet(attestor, true);
    }

    function setAttestor(address attestor, bool allowed) external {
        if (msg.sender != admin) revert NotAdmin();
        isAttestor[attestor] = allowed;
        emit AttestorSet(attestor, allowed);
    }

    function today() public view returns (int64) {
        return int64(int256(block.timestamp / 1 days));
    }

    function attestationKey(bytes32 identity, bytes32 mission, int64 day, uint8 seq) public pure returns (bytes32) {
        return keccak256(abi.encode(identity, mission, day, seq));
    }

    /// @notice Record one verified session. Each (identity, mission, day, seq) can be recorded exactly once.
    function record(bytes32 identity, bytes32 mission, int64 day, uint8 seq, bytes32 evidenceRoot, uint8 assurance)
        external
    {
        if (!isAttestor[msg.sender]) revert NotAttestor();
        if (assurance < 1 || assurance > 4) revert BadAssurance();
        int64 t = today();
        if (day != t && day != t - 1) revert DayOutOfRange();
        bytes32 key = attestationKey(identity, mission, day, seq);
        if (_attestations[key].recordedAt != 0) revert AlreadyAttested();
        _attestations[key] = Attestation(evidenceRoot, assurance, uint64(block.timestamp), msg.sender);

        Profile storage p = _profiles[identity];
        if (p.attestations == 0) p.lastDay = -1;
        int64 gap = day - p.lastDay;
        if (gap == 1) p.streak += 1;
        else if (gap != 0) p.streak = 1; // gap 0: another claim on a day already counted
        if (p.streak > p.bestStreak) p.bestStreak = p.streak;
        if (day > p.lastDay) p.lastDay = day;
        p.attestations += 1;
        p.reputation += assurance;
        p.lastAssurance = assurance;
        emit PresenceAttested(identity, mission, day, seq, evidenceRoot, assurance, p.streak, msg.sender);
    }

    /// @notice True if `identity` has an attestation of at least `minAssurance` within the last `maxAgeDays` UTC days.
    function isPresent(bytes32 identity, uint8 minAssurance, uint32 maxAgeDays) external view returns (bool) {
        Profile storage p = _profiles[identity];
        return p.attestations > 0 && p.lastAssurance >= minAssurance && today() - p.lastDay <= int64(uint64(maxAgeDays));
    }

    function profileOf(bytes32 identity) external view returns (Profile memory) {
        return _profiles[identity];
    }

    function attestationOf(bytes32 identity, bytes32 mission, int64 day, uint8 seq)
        external
        view
        returns (Attestation memory)
    {
        return _attestations[attestationKey(identity, mission, day, seq)];
    }
}
