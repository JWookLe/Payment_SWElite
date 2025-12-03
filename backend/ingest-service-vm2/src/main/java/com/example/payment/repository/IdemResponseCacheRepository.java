package com.example.payment.repository;

import com.example.payment.domain.IdemResponseCache;
import com.example.payment.domain.IdemResponseCacheId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdemResponseCacheRepository extends JpaRepository<IdemResponseCache, IdemResponseCacheId> {
}
