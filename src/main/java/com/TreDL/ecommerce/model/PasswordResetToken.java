package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="password_reset_tokens",uniqueConstraints=@UniqueConstraint(name="uq_password_reset_hash",columnNames="token_hash"))
public class PasswordResetToken {
    @Id @Column(name="id_customer") private Long customerId;
    @Column(name="token_hash",nullable=false,length=32,columnDefinition="BINARY(32)") private byte[] tokenHash;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
    protected PasswordResetToken() {}
    public PasswordResetToken(Long customerId,byte[] hash,LocalDateTime expiresAt) {this.customerId=customerId;replace(hash,expiresAt);}
    public void replace(byte[] hash,LocalDateTime expiry) {tokenHash=hash.clone();expiresAt=expiry;}
    public byte[] getTokenHash() {return tokenHash.clone();}
    public LocalDateTime getExpiresAt() {return expiresAt;}
}
