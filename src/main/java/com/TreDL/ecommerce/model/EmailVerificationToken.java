package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="email_verification_tokens",uniqueConstraints=@UniqueConstraint(name="uq_email_verification_hash",columnNames="token_hash"))
public class EmailVerificationToken {
    @Id @Column(name="id_customer") private Long customerId;
    @Column(name="token_hash",length=32,nullable=false,columnDefinition="BINARY(32)") private byte[] hash;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
    protected EmailVerificationToken(){}
    public EmailVerificationToken(Long id,byte[] hash,LocalDateTime expiry){customerId=id;replace(hash,expiry);}
    public void replace(byte[] value,LocalDateTime expiry){hash=value.clone();expiresAt=expiry;}
    public byte[] getHash(){return hash.clone();}
    public LocalDateTime getExpiresAt(){return expiresAt;}
}
