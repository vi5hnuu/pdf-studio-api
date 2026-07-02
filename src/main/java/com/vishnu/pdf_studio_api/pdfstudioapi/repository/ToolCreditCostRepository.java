package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.ToolCreditCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolCreditCostRepository extends JpaRepository<ToolCreditCost, String> {

    List<ToolCreditCost> findByActiveTrueOrderByToolIdAsc();
}
