package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccJournalLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccJournalLineRepository extends JpaRepository<AccJournalLine, Long> {
    List<AccJournalLine> findByPidJournalOrderByLineNoAsc(Long pidJournal);
}
